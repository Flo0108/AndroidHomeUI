package com.homelauncher.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

/** Static Wiener Linien OGD geometry plus live monitor responses. Vehicle markers are estimates, not GPS. */
internal class WienerLinienData(private val context: Context) {
    private data class Stop(val id: String, val name: String, val lat: Double, val lon: Double)
    private data class Point(val lat: Double, val lon: Double)
    private data class Track(val lineId: String, val from: String, val to: String, val points: List<Point>)
    private val stops by lazy { readStops() }
    private val lineNames by lazy { readLines() }
    private val segments by lazy { readSegments() }
    private val tracks by lazy { readTracks() }

    private fun csv(file: String): List<List<String>> = context.assets.open("wienerlinien/$file").bufferedReader(Charsets.UTF_8).useLines { lines ->
        lines.drop(1).map { it.removePrefix("\uFEFF").split(';') }.filter { it.size > 1 }.toList()
    }

    private fun readStops() = csv("haltepunkte.csv").mapNotNull { row ->
        val lon = row.getOrNull(5)?.toDoubleOrNull(); val lat = row.getOrNull(6)?.toDoubleOrNull()
        if (lon == null || lat == null) null else Stop(row[0], row.getOrNull(2).orEmpty(), lat, lon)
    }

    private fun readLines() = csv("linien.csv").associate { it[0] to it.getOrElse(1) { "" } }

    private fun readSegments(): Map<String, List<Point>> {
        val grouped = LinkedHashMap<String, MutableList<Pair<Int, Point>>>()
        csv("gps-punkte.csv").forEach { row ->
            val from = row.getOrNull(0) ?: return@forEach; val to = row.getOrNull(1) ?: return@forEach
            val seq = row.getOrNull(3)?.toIntOrNull() ?: return@forEach
            val lon = row.getOrNull(4)?.toDoubleOrNull() ?: return@forEach
            val lat = row.getOrNull(5)?.toDoubleOrNull() ?: return@forEach
            grouped.getOrPut("$from|$to") { ArrayList() }.add(seq to Point(lat, lon))
        }
        return grouped.mapValues { (_, points) -> points.sortedBy { it.first }.map { it.second } }
    }

    private fun readTracks(): List<Track> {
        val patterns = LinkedHashMap<String, MutableList<Pair<Int, String>>>()
        csv("fahrwegverlaeufe.csv").forEach { row ->
            val line = row.getOrNull(0) ?: return@forEach
            val pattern = row.getOrNull(1) ?: return@forEach
            val seq = row.getOrNull(2)?.toIntOrNull() ?: return@forEach
            val stop = row.getOrNull(3) ?: return@forEach
            val direction = row.getOrNull(4).orEmpty()
            patterns.getOrPut("$line|$pattern|$direction") { ArrayList() }.add(seq to stop)
        }
        val result = LinkedHashMap<String, Track>()
        patterns.forEach { (key, ordered) ->
            val line = key.substringBefore('|')
            ordered.sortedBy { it.first }.zipWithNext().forEach segment@ { (a, b) ->
                val points = segments["${a.second}|${b.second}"] ?: segments["${b.second}|${a.second}"]?.reversed() ?: return@segment
                val featureKey = "$line|${a.second}|${b.second}"
                result.putIfAbsent(featureKey, Track(line, a.second, b.second, points))
            }
        }
        return result.values.toList()
    }

    /** One batched monitor request for the closest platforms; official Fair Use minimum is 15 seconds. */
    fun fetch(lat: Double, lon: Double, zoom: Int): JSONObject {
        val radiusMeters = when { zoom < 11 -> 4500.0; zoom < 14 -> 2600.0; else -> 1500.0 }
        val selected = stops.asSequence().map { it to distanceMeters(lat, lon, it.lat, it.lon) }
            .filter { it.second <= radiusMeters }.sortedBy { it.second }.take(10).map { it.first }.toList()
        if (selected.isEmpty()) return JSONObject().put("stops", JSONArray()).put("tracks", JSONArray()).put("message", "No Wiener Linien stops in this area")

        val query = selected.joinToString("&") { "stopId=${it.id}" }
        val connection = (URL("https://www.wienerlinien.at/ogd_realtime/monitor?$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 10000; readTimeout = 12000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "HomeLauncher Android map")
        }
        val response = try {
            if (connection.responseCode !in 200..299) error("Wiener Linien HTTP ${connection.responseCode}")
            JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { connection.disconnect() }

        val activeLineIds = LinkedHashSet<String>()
        val stopJson = JSONArray()
        val monitors = response.optJSONObject("data")?.optJSONArray("monitors") ?: JSONArray()
        for (i in 0 until monitors.length()) {
            val monitor = monitors.optJSONObject(i) ?: continue
            val place = monitor.optJSONObject("locationStop") ?: continue
            val properties = place.optJSONObject("properties") ?: JSONObject()
            val geometry = place.optJSONObject("geometry")?.optJSONArray("coordinates")
            val slon = geometry?.optDouble(0, Double.NaN) ?: Double.NaN
            val slat = geometry?.optDouble(1, Double.NaN) ?: Double.NaN
            val staticStop = selected.firstOrNull { it.id == properties.optJSONObject("attributes")?.optInt("rbl", -1)?.toString() }
            val linesJson = JSONArray()
            val lines = monitor.optJSONArray("lines") ?: JSONArray()
            val lineBrief = ArrayList<String>()
            for (j in 0 until lines.length()) {
                val line = lines.optJSONObject(j) ?: continue
                val name = line.optString("name").takeIf { it.isNotBlank() } ?: continue
                val departures = line.optJSONObject("departures")?.optJSONArray("departure") ?: JSONArray()
                if (departures.length() == 0) continue
                val lineId = line.optString("lineId")
                if (lineId.isNotBlank()) activeLineIds.add(lineId)
                val first = departures.optJSONObject(0)?.optJSONObject("departureTime")
                val eta = first?.optInt("countdown", -1) ?: -1
                val toward = line.optString("towards", "")
                linesJson.put(JSONObject().put("name", name).put("towards", toward).put("eta", eta).put("type", line.optString("type")))
                lineBrief.add(if (eta >= 0) "$name ${eta}′" else name)
            }
            if (linesJson.length() == 0 || !slat.isFinite() || !slon.isFinite()) continue
            stopJson.put(JSONObject().put("id", properties.optString("name"))
                .put("name", properties.optString("title", staticStop?.name ?: "Wiener Linien stop"))
                .put("lat", slat).put("lon", slon).put("lines", linesJson)
                .put("summary", lineBrief.joinToString(" · ")))
        }

        val selectedIds = selected.map { it.id }.toHashSet()
        val trackJson = JSONArray()
        tracks.asSequence().filter { it.lineId in activeLineIds && (it.from in selectedIds || it.to in selectedIds) }
            .take(320).forEach { track ->
                val points = JSONArray()
                track.points.forEach { points.put(JSONArray().put(it.lat).put(it.lon)) }
                trackJson.put(JSONObject().put("line", lineNames[track.lineId] ?: track.lineId).put("points", points))
            }
        return JSONObject().put("stops", stopJson).put("tracks", trackJson)
            .put("queried", selected.size).put("updatedAt", System.currentTimeMillis())
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 6371000.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
