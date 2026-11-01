package com.homelauncher.app

import android.animation.ValueAnimator
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.provider.CalendarContract
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.*
import android.view.animation.LinearInterpolator
import android.widget.*
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.text.SimpleDateFormat
import java.text.DateFormat
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.concurrent.Executors
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

private const val IVORY = 0xfff6f3ed.toInt()
private const val GRAPHITE = 0xff1b201e.toInt()
private const val TEXT_DARK = 0xff252422.toInt()
private const val MUTED_LIGHT = 0xff77736d.toInt()
private const val MUTED_DARK = 0xffc1c3bd.toInt()
    private const val REQUEST_LOCATION = 3101
private const val REQUEST_CALENDAR = 3102
private const val REQUEST_MAP_LOCATION = 3103
private data class Palette(val bg: Int, val fg: Int, val muted: Int, val plan: Int, val accent: Int, val lightSystem: Boolean)
private data class LaunchableApp(val info:android.content.pm.ApplicationInfo,val name:String)
private data class GridPlacement(val app:LaunchableApp,val row:Int,val column:Int,val span:Int)
private fun palette(hour: Int) = if (hour in 7..18) Palette(GRAPHITE, IVORY, MUTED_DARK, IVORY, 0xffbd765c.toInt(), false)
else Palette(IVORY, TEXT_DARK, MUTED_LIGHT, TEXT_DARK, 0xff79392e.toInt(), true)
private interface Themed { fun theme(p: Palette) }

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var pager: LauncherPager
    private var activityResumed=false
    private var current = palette(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    private val ticker = object : Runnable {
        override fun run() {
            val next = palette(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
            if (next != current) { current = next; applyTheme() }
            pager.updateClock()
            handler.postDelayed(this, 30_000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pager = LauncherPager()
        setContentView(pager)
        applyTheme()
    }
    override fun onResume() { super.onResume(); activityResumed=true;handler.post(ticker); if(::pager.isInitialized){pager.refreshDailyGlance();pager.setWeatherMotionActive(true)} }
    override fun onPause() { activityResumed=false;handler.removeCallbacks(ticker); if(::pager.isInitialized)pager.setWeatherMotionActive(false); super.onPause() }
    private fun lifecycleIsResumed()=activityResumed
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(::pager.isInitialized){val granted=grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED;pager.permissionResult(requestCode,granted);pager.mapPermissionResult(requestCode,granted)}
    }
    private fun applyTheme() {
        window.statusBarColor = current.bg; window.navigationBarColor = current.bg
        window.decorView.systemUiVisibility = if (current.lightSystem) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        pager.theme(current)
    }
    override fun onBackPressed() { if (::pager.isInitialized && pager.page != 0) pager.showPage(0) else super.onBackPressed() }

    private inner class LauncherPager : FrameLayout(this@MainActivity) {
        var page = 0; private var downX = 0f; private var downY = 0f
        private val dots = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        private val home = HomeScreen(); private val apps = AppsScreen(); private val map = MapScreen()
        init {
            clipChildren = true
            addView(home, LayoutParams(-1, -1))
            addView(apps, LayoutParams(-1, -1))
            addView(map, LayoutParams(-1,-1))
            val nav = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(28)); addView(dots) }
            addView(nav, LayoutParams(-1, -2, Gravity.BOTTOM))
            repeat(3) { index ->
                val target=FrameLayout(this@MainActivity).apply{contentDescription="Page ${index+1}";isClickable=true;isFocusable=true;setOnClickListener{showPage(index)}
                    addView(View(this@MainActivity),FrameLayout.LayoutParams(dp(5),dp(5),Gravity.CENTER))}
                dots.addView(target,LinearLayout.LayoutParams(dp(34),dp(34)))
            }
            showPage(0)
        }
        fun theme(p: Palette) { setBackgroundColor(p.bg); home.theme(p); apps.theme(p);map.theme(p); updateDots(p) }
        private fun updateDots(p:Palette){for(i in 0..2)(dots.getChildAt(i) as FrameLayout).getChildAt(0).setBackgroundColor(if(i==page)p.fg else p.muted)}
        fun updateClock() = home.updateClock()
        fun refreshDailyGlance() = home.refreshIfPermitted()
        fun permissionResult(code:Int,granted:Boolean) = home.permissionResult(code,granted)
        fun mapPermissionResult(code:Int,granted:Boolean)=map.permissionResult(code,granted)
        fun setWeatherMotionActive(active:Boolean) = home.setWeatherMotionActive(active && page==0)
        fun showPage(index: Int) {
            page=index.coerceIn(0,2)
            home.visibility=if(page==0)View.VISIBLE else View.GONE
            apps.visibility=if(page==1)View.VISIBLE else View.GONE
            map.visibility=if(page==2)View.VISIBLE else View.GONE
            home.setWeatherMotionActive(page==0 && lifecycleIsResumed())
            if(page==2)map.onShown() else map.onHidden()
            updateDots(current)
        }
        override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
            when(e.action){MotionEvent.ACTION_DOWN->{downX=e.x;downY=e.y};MotionEvent.ACTION_MOVE->{val dx=kotlin.math.abs(e.x-downX);val dy=kotlin.math.abs(e.y-downY);return dx>dp(32)&&dx>dy*1.2f}}
            return false
        }
        override fun onTouchEvent(e: MotionEvent): Boolean { if(e.action==MotionEvent.ACTION_UP){val dx=e.x-downX;if(kotlin.math.abs(dx)>dp(40))showPage(page+if(dx<0)1 else -1);return true};return true }
    }

    private inner class HomeScreen : FrameLayout(this@MainActivity), Themed {
        private val weatherScene=HomeWeatherScene(this@MainActivity)
        private val clock = label("--:--", 76f).apply{gravity=Gravity.CENTER;textAlignment=View.TEXT_ALIGNMENT_CENTER}
        private val date = label("", 14f)
        private val weatherValue=label("Tap to set",16f)
        private val weatherDetail=label("LOCAL WEATHER",9f)
        private val weatherMotion=WeatherMotionView(this@MainActivity)
        private val eventValue=label("Tap to connect",15f)
        private val eventDetail=label("UP NEXT",9f)
        private val weatherCell=LinearLayout(this@MainActivity).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            addView(glanceCell(weatherDetail,weatherValue),LinearLayout.LayoutParams(0,-2,1f))
            addView(weatherMotion,LinearLayout.LayoutParams(dp(42),dp(42)))
        }
        private val eventCell=glanceCell(eventDetail,eventValue)
        private val glance=LinearLayout(this@MainActivity).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            background=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;cornerRadius=dp(18).toFloat()}
            setPadding(dp(16),dp(14),dp(16),dp(14))
            addView(weatherCell,LinearLayout.LayoutParams(0,-2,1f))
            val divider=View(this@MainActivity).apply{tag="divider"}
            addView(divider,LinearLayout.LayoutParams(dp(1),dp(42)))
            addView(eventCell,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(14)})
        }
        init {
            addView(weatherScene,LayoutParams(-1,-1))
            val topStack=LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.VERTICAL
                gravity=Gravity.CENTER_HORIZONTAL
                setPadding(dp(26),dp(84),dp(26),0)
                addView(clock,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
                date.gravity=Gravity.CENTER
                date.setPadding(0,dp(4),0,0)
                addView(date,LinearLayout.LayoutParams(-1,-2))
                addView(glance,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(34)})
            }
            addView(topStack,LayoutParams(-1,-1))
            weatherCell.setOnClickListener{if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED)loadWeather() else requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),REQUEST_LOCATION)}
            eventCell.setOnClickListener{if(checkSelfPermission(Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED)loadEvent() else requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR),REQUEST_CALENDAR)}
            updateClock()
        }
        private fun glanceCell(kicker:TextView,value:TextView)=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;addView(kicker);value.setPadding(0,dp(7),0,0);value.maxLines=1;value.ellipsize=android.text.TextUtils.TruncateAt.END;addView(value)}
        override fun theme(p:Palette){
            setBackgroundColor(p.bg);weatherScene.setColors(p.muted,p.accent);clock.setTextColor(p.fg);date.setTextColor(p.muted)
            glance.background=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;cornerRadius=dp(18).toFloat();setColor(Color.argb(11,Color.red(p.fg),Color.green(p.fg),Color.blue(p.fg)));setStroke(dp(1),Color.argb(28,Color.red(p.muted),Color.green(p.muted),Color.blue(p.muted)))}
            weatherDetail.setTextColor(p.muted);eventDetail.setTextColor(p.muted);weatherValue.setTextColor(p.fg);eventValue.setTextColor(p.fg);weatherMotion.setColors(p.muted,p.accent)
            (glance.getChildAt(1))?.setBackgroundColor(Color.argb(45,Color.red(p.muted),Color.green(p.muted),Color.blue(p.muted)))
        }
        fun updateClock(){val now=Date();clock.text=SimpleDateFormat("HH:mm",Locale.getDefault()).format(now);date.text=SimpleDateFormat("EEEE · d MMMM",Locale.getDefault()).format(now).uppercase(Locale.getDefault())}
        fun permissionResult(code:Int,granted:Boolean){if(granted){if(code==REQUEST_LOCATION)loadWeather();if(code==REQUEST_CALENDAR)loadEvent()}}
        fun refreshIfPermitted(){if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED)loadWeather();if(checkSelfPermission(Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED)loadEvent()}
        private fun loadWeather(){
            weatherValue.text="Finding location…"
            try{
                val lm=getSystemService(LOCATION_SERVICE) as LocationManager
                val cached=listOf(LocationManager.NETWORK_PROVIDER,LocationManager.PASSIVE_PROVIDER).mapNotNull{provider->try{lm.getLastKnownLocation(provider)}catch(_:Exception){null}}.maxByOrNull{it.time}
                if(cached!=null)fetchWeather(cached) else if(android.os.Build.VERSION.SDK_INT>=30){
                    val provider=if(lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))LocationManager.NETWORK_PROVIDER else LocationManager.PASSIVE_PROVIDER
                    lm.getCurrentLocation(provider,null,mainExecutor){loc->if(loc!=null)fetchWeather(loc)else runOnUiThread{weatherValue.text="Location unavailable"}}
                }else weatherValue.text="Tap to retry"
            }catch(_:Exception){weatherValue.text="Tap to retry"}
        }
        private fun fetchWeather(loc:Location){Thread{
            try{
                val url=URL("https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}&longitude=${loc.longitude}&current=temperature_2m,weather_code,wind_gusts_10m&timezone=auto")
                val connection=url.openConnection() as HttpURLConnection;connection.connectTimeout=8000;connection.readTimeout=8000
                val json=JSONObject(connection.inputStream.bufferedReader().use{it.readText()});connection.disconnect()
                val current=json.getJSONObject("current");val temp=current.getDouble("temperature_2m").toInt();val code=current.getInt("weather_code");val summary=weatherLabel(code)
                val gusts=current.optDouble("wind_gusts_10m",0.0)
                runOnUiThread{weatherValue.text="$temp° · $summary";weatherMotion.setWeather(code,gusts);weatherScene.setWeather(code,gusts)}
            }catch(_:Exception){runOnUiThread{weatherValue.text="Weather unavailable"}}
        }.start()}
        private fun weatherLabel(code:Int)=when(code){0->"Clear";1,2->"Partly cloudy";3->"Cloudy";45,48->"Fog";in 51..67->"Rain";in 71..77->"Snow";in 80..82->"Showers";in 85..86->"Snow";in 95..99->"Storm";else->"Today"}
        private fun loadEvent(){
            try{
                val now=System.currentTimeMillis();val end=now+7L*24*60*60*1000
                val uri=CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(now.toString()).appendPath(end.toString()).build()
                val projection=arrayOf(CalendarContract.Instances.TITLE,CalendarContract.Instances.BEGIN,CalendarContract.Instances.ALL_DAY)
                contentResolver.query(uri,projection,"${CalendarContract.Instances.BEGIN} >= ?",arrayOf(now.toString()),"${CalendarContract.Instances.BEGIN} ASC")?.use{cursor->
                    if(cursor.moveToFirst()){
                        val title=cursor.getString(0).orEmpty();val begin=cursor.getLong(1);val allDay=cursor.getInt(2)!=0
                        eventValue.text=if(allDay)title else "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(begin))}  $title"
                        return
                    }
                }
                eventValue.text="Nothing scheduled"
            }catch(_:Exception){eventValue.text="Calendar unavailable"}
        }
        fun setWeatherMotionActive(active:Boolean){weatherMotion.setActive(active);weatherScene.setActive(active)}
    }

    private inner class MapScreen : FrameLayout(this@MainActivity),Themed {
        private val mapWeb=WebView(this@MainActivity)
        private val locate=TextView(this@MainActivity).apply{text="◎";textSize=25f;gravity=Gravity.CENTER;contentDescription="Show my current location";isClickable=true;isFocusable=true}
        private val status=label("TAP ◎ TO LOCATE",10f)
        private val apiButton=TextView(this@MainActivity).apply{text="⚙";textSize=22f;gravity=Gravity.CENTER;contentDescription="Map data and API keys";isClickable=true;isFocusable=true}
        private val transitButton=TextView(this@MainActivity).apply{text="WIENER LINIEN · LIVE";textSize=10f;gravity=Gravity.CENTER;setPadding(dp(12),dp(8),dp(12),dp(8));isClickable=true;isFocusable=true}
        private val mapPreferences=getSharedPreferences("launcher",MODE_PRIVATE)
        private val transitWorker=Executors.newSingleThreadExecutor()
        private val transitHandler=Handler(Looper.getMainLooper())
        private val transitCatalog by lazy{WienerLinienData(this@MainActivity)}
        @Volatile private var transitLat=48.2082
        @Volatile private var transitLon=16.3738
        @Volatile private var transitZoom=12
        private var transitEnabled=mapPreferences.getBoolean("wl_live_enabled",true)
        private var screenVisible=false
        private var refreshSeconds=mapPreferences.getInt("wl_refresh_seconds",30).coerceIn(20,120)
        private var lastTransitFetchElapsed=0L
        private val pollTransit=object:Runnable{override fun run(){if(transitEnabled&&screenVisible)fetchTransit()}}
        private var mapReady=false
        private var mapLoaded=false
        private var lastLocation:Location?=null
        private var latestTransit:JSONObject?=null
        init{
            addView(mapWeb,LayoutParams(-1,-1))
            addView(status,LayoutParams(-2,-2,Gravity.TOP or Gravity.START).apply{leftMargin=dp(18);topMargin=dp(22)})
            addView(transitButton,LayoutParams(-2,dp(38),Gravity.TOP or Gravity.START).apply{leftMargin=dp(18);topMargin=dp(48)})
            addView(apiButton,LayoutParams(dp(48),dp(48),Gravity.TOP or Gravity.END).apply{rightMargin=dp(18);topMargin=dp(14)})
            addView(locate,LayoutParams(dp(48),dp(48),Gravity.TOP or Gravity.END).apply{rightMargin=dp(18);topMargin=dp(68)})
            locate.setOnClickListener{locateUser()}
            apiButton.setOnClickListener{showApiSettings()}
            transitButton.setOnClickListener{setTransitEnabled(!transitEnabled)}
            mapWeb.settings.javaScriptEnabled=true
            mapWeb.settings.domStorageEnabled=true
            mapWeb.settings.cacheMode=android.webkit.WebSettings.LOAD_DEFAULT
            mapWeb.settings.userAgentString="${mapWeb.settings.userAgentString} HomeLauncher/4.0"
            mapWeb.addJavascriptInterface(object{
                @JavascriptInterface fun requestLocation(){runOnUiThread{locateUser()}}
                @JavascriptInterface fun reportViewport(lat:Double,lon:Double,zoom:Int){transitLat=lat;transitLon=lon;transitZoom=zoom.coerceIn(4,18)}
            },"HomeLauncher")
            mapWeb.webViewClient=object:WebViewClient(){override fun onPageFinished(view:WebView?,url:String?){mapReady=true;mapWeb.evaluateJavascript("window.setTransitEnabled($transitEnabled)",null);latestTransit?.let{publishTransit(it)};lastLocation?.let{showLocation(it)} }}
            var pageSwipeFromEdge=false
            mapWeb.setOnTouchListener{view,event->
                if(event.action==MotionEvent.ACTION_DOWN){
                    val edge=dp(36)
                    pageSwipeFromEdge=event.x<=edge||event.x>=view.width-edge
                }
                if(event.action==MotionEvent.ACTION_DOWN||event.action==MotionEvent.ACTION_MOVE){
                    // Keep map gestures in the interior, but let the pager own edge swipes.
                    view.parent?.requestDisallowInterceptTouchEvent(!pageSwipeFromEdge)
                }
                if(event.action==MotionEvent.ACTION_UP||event.action==MotionEvent.ACTION_CANCEL){
                    pageSwipeFromEdge=false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
        override fun theme(p:Palette){
            setBackgroundColor(p.bg);status.setTextColor(p.muted);locate.setTextColor(p.fg);apiButton.setTextColor(p.fg);transitButton.setTextColor(p.fg)
            transitButton.background=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;cornerRadius=dp(16).toFloat();setColor(Color.argb(225,Color.red(p.bg),Color.green(p.bg),Color.blue(p.bg)));setStroke(dp(1),Color.argb(80,Color.red(p.muted),Color.green(p.muted),Color.blue(p.muted)))}
            locate.background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(Color.argb(225,Color.red(p.bg),Color.green(p.bg),Color.blue(p.bg)));setStroke(dp(1),Color.argb(75,Color.red(p.muted),Color.green(p.muted),Color.blue(p.muted)))}
            applyMapTheme(p)
        }
        private fun setTransitEnabled(enabled:Boolean){
            transitEnabled=enabled;mapPreferences.edit().putBoolean("wl_live_enabled",enabled).apply()
            transitButton.text=if(enabled)"WIENER LINIEN · LIVE · ${refreshSeconds}s" else "WIENER LINIEN · OFF"
            if(mapReady)mapWeb.evaluateJavascript("window.setTransitEnabled($enabled)",null)
            transitHandler.removeCallbacks(pollTransit)
            if(enabled&&screenVisible)fetchTransit()
        }
        private fun fetchTransit(){
            if(!transitEnabled||!screenVisible)return
            val now=android.os.SystemClock.elapsedRealtime()
            val fairUseWait=15_000L-(now-lastTransitFetchElapsed)
            if(lastTransitFetchElapsed>0L&&fairUseWait>0L){transitHandler.removeCallbacks(pollTransit);transitHandler.postDelayed(pollTransit,fairUseWait);return}
            lastTransitFetchElapsed=now
            transitHandler.removeCallbacks(pollTransit)
            transitButton.text="WIENER LINIEN · UPDATING…"
            transitWorker.execute{
                try{
                    val payload=transitCatalog.fetch(transitLat,transitLon,transitZoom)
                    runOnUiThread{
                        if(!screenVisible||!transitEnabled)return@runOnUiThread
                        latestTransit=payload
                        if(mapReady)publishTransit(payload)
                        val count=payload.optJSONArray("stops")?.length()?:0
                        transitButton.text=if(count>0)"WIENER LINIEN · LIVE · ${refreshSeconds}s" else "WIENER LINIEN · NO STOPS HERE"
                        val nearest=payload.optJSONArray("stops")?.optJSONObject(0)
                        status.text=if(nearest!=null)"${nearest.optString("name")}  ·  ${nearest.optString("summary")}" else payload.optString("message","NO LIVE STOPS NEARBY")
                        transitHandler.postDelayed(pollTransit,refreshSeconds*1000L)
                    }
                }catch(error:Exception){runOnUiThread{
                    if(screenVisible&&transitEnabled){transitButton.text="WIENER LINIEN · RETRYING";transitHandler.postDelayed(pollTransit,refreshSeconds*1000L)}
                }}
            }
        }
        private fun publishTransit(payload:JSONObject){
            val bytes=Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8),Base64.NO_WRAP)
            mapWeb.evaluateJavascript("window.setTransitDataEncoded('$bytes')",null)
        }
        private fun applyMapTheme(p:Palette){if(mapReady)mapWeb.evaluateJavascript("window.setDarkMap(${p.bg==GRAPHITE})",null)}
        private val apiKeyPreferences by lazy{getSharedPreferences("map_api_keys",MODE_PRIVATE)}
        private val apiKeyPrefix="enc:"
        private val apiKeyAlias="homelauncher_map_api_keys_v1"
        private fun encryptionKey():java.security.Key{
            val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
            store.getKey(apiKeyAlias,null)?.let{return it}
            val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(apiKeyAlias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
            return generator.generateKey()
        }
        private fun apiKeyNames()=apiKeyPreferences.all.keys.filter{it.startsWith(apiKeyPrefix)}.mapNotNull{key->try{String(Base64.decode(key.removePrefix(apiKeyPrefix),Base64.NO_WRAP),Charsets.UTF_8)}catch(_:Exception){null}}.sorted()
        private fun saveApiKey(name:String,value:String){
            val key=apiKeyPrefix+Base64.encodeToString(name.toByteArray(Charsets.UTF_8),Base64.NO_WRAP)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,encryptionKey())}
            apiKeyPreferences.edit().putString(key,Base64.encodeToString(cipher.iv+cipher.doFinal(value.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)).apply()
        }
        @Suppress("unused") private fun readApiKey(name:String):String? {
            return try{
                val encoded=apiKeyPreferences.getString(apiKeyPrefix+Base64.encodeToString(name.toByteArray(Charsets.UTF_8),Base64.NO_WRAP),null)?:return null
                val packed=Base64.decode(encoded,Base64.NO_WRAP)
                val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,encryptionKey(),GCMParameterSpec(128,packed.copyOfRange(0,12)))}
                String(cipher.doFinal(packed.copyOfRange(12,packed.size)),Charsets.UTF_8)
            }catch(_:Exception){null}
        }
        private fun removeApiKey(name:String){apiKeyPreferences.edit().remove(apiKeyPrefix+Base64.encodeToString(name.toByteArray(Charsets.UTF_8),Base64.NO_WRAP)).apply()}
        private fun showApiSettings(){
            val panel=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(8))}
            panel.addView(TextView(this@MainActivity).apply{text="WIENER LINIEN · OPEN DATA\nLive nearby departures. Route motion is estimated from arrivals, not GPS vehicle positions.";textSize=14f;setTextColor(current.fg)},LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
            panel.addView(TextView(this@MainActivity).apply{text="REFRESH INTERVAL · MINIMUM 20 SECONDS";textSize=10f;setTextColor(current.muted)})
            val intervals=listOf(20,30,60,120)
            val intervalPicker=Spinner(this@MainActivity).apply{
                adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,intervals.map{"$it seconds"})
                setSelection(intervals.indexOf(refreshSeconds).coerceAtLeast(0))
                onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
                    override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
                    override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){
                        refreshSeconds=intervals[position];mapPreferences.edit().putInt("wl_refresh_seconds",refreshSeconds).apply()
                        if(transitEnabled&&screenVisible){transitHandler.removeCallbacks(pollTransit);fetchTransit()}
                        else if(transitEnabled)transitButton.text="WIENER LINIEN · LIVE · ${refreshSeconds}s"
                    }
                }
            };panel.addView(intervalPicker)
            val provider=EditText(this@MainActivity).apply{hint="Provider name (e.g. ÖBB)";setSingleLine(true)}
            val secret=EditText(this@MainActivity).apply{hint="API key";setSingleLine(true);inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD}
            panel.addView(provider);panel.addView(secret)
            val saved=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL}
            panel.addView(TextView(this@MainActivity).apply{text="SAVED KEYS";textSize=10f;setTextColor(current.muted);setPadding(0,dp(14),0,dp(4))});panel.addView(saved)
            fun refresh(){
                saved.removeAllViews()
                val names=apiKeyNames()
                names.forEach{name->val row=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
                    row.addView(TextView(this@MainActivity).apply{text="$name  ·  key stored";textSize=13f;setTextColor(current.fg)},LinearLayout.LayoutParams(0,-2,1f))
                    row.addView(TextView(this@MainActivity).apply{text="REMOVE";textSize=10f;setTextColor(current.accent);setPadding(dp(8),dp(10),0,dp(10));setOnClickListener{removeApiKey(name);refresh()} });saved.addView(row)}
                if(names.isEmpty())saved.addView(TextView(this@MainActivity).apply{text="No API keys saved";textSize=12f;setTextColor(current.muted)})
            }
            val dialog=AlertDialog.Builder(this@MainActivity).setTitle("MAP DATA SOURCES").setView(panel).setNegativeButton("CLOSE",null).create()
            panel.addView(Button(this@MainActivity).apply{text="SAVE KEY";setOnClickListener{
                val name=provider.text.toString().trim();val value=secret.text.toString()
                if(name.isBlank()||value.isBlank()){Toast.makeText(this@MainActivity,"Enter a provider and API key",Toast.LENGTH_SHORT).show();return@setOnClickListener}
                try{saveApiKey(name,value);secret.text.clear();refresh();Toast.makeText(this@MainActivity,"Key encrypted on this device",Toast.LENGTH_SHORT).show()}
                catch(_:Exception){Toast.makeText(this@MainActivity,"Could not securely save this key",Toast.LENGTH_LONG).show()}
            }});refresh();dialog.show()
        }
        fun onShown(){screenVisible=true;if(!mapLoaded){mapLoaded=true;mapWeb.loadDataWithBaseURL("https://www.openstreetmap.org/",mapHtml(current.bg==GRAPHITE),"text/html","UTF-8",null)};val coarse=checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;val fine=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;if(fine||(coarse&&mapPreferences.getBoolean("map_precision_asked",false)))readCurrentLocation();if(transitEnabled)fetchTransit()}
        fun onHidden(){screenVisible=false;transitHandler.removeCallbacks(pollTransit)}
        fun permissionResult(code:Int,granted:Boolean){if(code==REQUEST_MAP_LOCATION){if(granted)readCurrentLocation()else status.text="LOCATION OFF · TAP ◎ TO RETRY"}}
        private fun locateUser(){
            val coarse=checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
            val fine=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
            if(!coarse||(!fine&&!mapPreferences.getBoolean("map_precision_asked",false))){
                mapPreferences.edit().putBoolean("map_precision_asked",true).apply()
                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION),REQUEST_MAP_LOCATION);return
            }
            readCurrentLocation()
        }
        private fun readCurrentLocation(){
            status.text="FINDING YOUR LOCATION…"
            try{
                val manager=getSystemService(LOCATION_SERVICE) as LocationManager
                val fine=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
                val providers=buildList{if(fine&&manager.isProviderEnabled(LocationManager.GPS_PROVIDER))add(LocationManager.GPS_PROVIDER);if(manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))add(LocationManager.NETWORK_PROVIDER);if(manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER))add(LocationManager.PASSIVE_PROVIDER)}
                if(android.os.Build.VERSION.SDK_INT>=30&&providers.isNotEmpty()){
                    fun requestNext(index:Int){
                        if(index>=providers.size){showRecentLocation(manager,providers);return}
                        manager.getCurrentLocation(providers[index],null,mainExecutor){location->runOnUiThread{if(location!=null)showLocation(location)else requestNext(index+1)}}
                    }
                    requestNext(0)
                }else showRecentLocation(manager,providers)
            }catch(_:Exception){status.text="LOCATION UNAVAILABLE · TRY AGAIN"}
        }
        private fun showRecentLocation(manager:LocationManager,providers:List<String>){
            val now=System.currentTimeMillis()
            val fresh=providers.mapNotNull{try{manager.getLastKnownLocation(it)}catch(_:Exception){null}}.filter{now-it.time<120_000}.maxByOrNull{it.time}
            if(fresh!=null){status.text="LAST LOCATION FIX";showLocation(fresh)}else status.text="LOCATION UNAVAILABLE · TRY AGAIN"
        }
        private fun showLocation(location:Location){
            lastLocation=location
            if(status.text!="LAST LOCATION FIX")status.text="CURRENT LOCATION"
            if(mapReady)mapWeb.evaluateJavascript("window.setCurrentLocation(${location.latitude},${location.longitude},${location.accuracy})",null)
        }
    }

    private fun mapHtml(darkMode:Boolean)="""<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"><style>
        *{box-sizing:border-box}html,body,#map{margin:0;width:100%;height:100%;overflow:hidden;background:#e7e4de;font-family:Arial,sans-serif;touch-action:none}
        #tiles{position:absolute;inset:0;overflow:hidden}.tile{position:absolute;width:256px;height:256px;user-select:none;-webkit-user-drag:none}
        #transit{position:absolute;inset:0;width:100%;height:100%;overflow:visible;pointer-events:none;z-index:4}.transit-track{fill:none;stroke-width:3;stroke-linecap:round;stroke-linejoin:round;opacity:.26}.transit-flow{fill:none;stroke-width:3;stroke-linecap:round;stroke-dasharray:1 10;animation:snake 1.05s linear infinite}@keyframes snake{to{stroke-dashoffset:-22}}
        .wl-stop{fill:#f6f3ed;stroke:#bd765c;stroke-width:2}.wl-label{font:10px Arial,sans-serif;fill:#f6f3ed;paint-order:stroke;stroke:#1b201e;stroke-width:3px;stroke-linejoin:round}
        #marker{position:absolute;width:22px;height:22px;border:3px solid #fff;border-radius:50%;background:#bd765c;box-shadow:0 1px 8px #0008;transform:translate(-50%,-50%);display:none;z-index:5}
        #accuracy{position:absolute;width:20px;height:20px;border:1px solid #bd765c88;border-radius:50%;background:#bd765c18;transform:translate(-50%,-50%);display:none;z-index:3;pointer-events:none}
        #marker:after{content:'';position:absolute;inset:-8px;border:1.5px solid #bd765c;border-radius:50%;opacity:.55;animation:pulse 2.8s ease-out infinite}@keyframes pulse{0%{transform:scale(.55);opacity:.65}100%{transform:scale(1.55);opacity:0}}
        #controls{position:absolute;right:16px;top:78px;display:flex;flex-direction:column;gap:8px;z-index:8}.control{width:42px;height:42px;border:0;border-radius:50%;background:#f6f3eddf;color:#252422;font-size:24px;box-shadow:0 2px 12px #0002}
        #credit{position:absolute;right:5px;bottom:5px;z-index:8;background:#f6f3eddd;padding:3px 5px;border-radius:4px;color:#292824;font-size:10px}#credit a{color:inherit}
        body.dark #map{background:#1b201e}body.dark .tile{filter:grayscale(1) invert(.88) brightness(.8) contrast(.88)}body.dark #credit{background:#1b201edd;color:#eee}
        </style></head><body class="${if(darkMode) "dark" else ""}"><div id="map"><div id="tiles"></div><svg id="transit"></svg><div id="accuracy"></div><div id="marker"></div><div id="controls"><button class="control" onclick="zoom(1)">+</button><button class="control" onclick="zoom(-1)">−</button><button class="control" onclick="HomeLauncher.requestLocation()">◎</button></div><div id="credit"><a href="https://www.openstreetmap.org/copyright" target="_blank">© OSM · Wiener Linien OGD CC BY 4.0</a></div></div>
        <script>
        const map=document.getElementById('map'),tiles=document.getElementById('tiles'),marker=document.getElementById('marker'),accuracy=document.getElementById('accuracy'),transit=document.getElementById('transit');let center={lat:48.2082,lon:16.3738},user=null,z=12,dark=${darkMode},drag=null,pointers=new Map(),pinch=null,transitData=null,transitEnabled=true;
        const world=()=>256*Math.pow(2,z),px=()=>({x:(center.lon+180)/360*world(),y:(1-Math.log(Math.tan(center.lat*Math.PI/180)+1/Math.cos(center.lat*Math.PI/180))/Math.PI)/2*world()});
        function fromPx(x,y){const n=world();center.lon=x/n*360-180;center.lat=Math.atan(Math.sinh(Math.PI*(1-2*y/n)))*180/Math.PI;center.lat=Math.max(-85,Math.min(85,center.lat))}
        function render(){const w=map.clientWidth,h=map.clientHeight,c=px(),left=Math.floor((c.x-w/2)/256),right=Math.floor((c.x+w/2)/256),top=Math.floor((c.y-h/2)/256),bottom=Math.floor((c.y+h/2)/256);tiles.innerHTML='';const n=Math.pow(2,z);for(let ty=top;ty<=bottom;ty++){if(ty<0||ty>=n)continue;for(let tx=left;tx<=right;tx++){const wrapped=((tx%n)+n)%n,img=document.createElement('img');img.className='tile';img.alt='';img.draggable=false;img.src='https://tile.openstreetmap.org/'+z+'/'+wrapped+'/'+ty+'.png';img.style.left=(tx*256-(c.x-w/2))+'px';img.style.top=(ty*256-(c.y-h/2))+'px';tiles.appendChild(img)}}positionMarker(c,w,h);renderTransit()}
        function positionMarker(c=px(),w=map.clientWidth,h=map.clientHeight){if(!user){marker.style.display='none';accuracy.style.display='none';return}const u=project(user.lat,user.lon),x=w/2+u.x-c.x,y=h/2+u.y-c.y;marker.style.display='block';marker.style.left=x+'px';marker.style.top=y+'px';accuracy.style.display='block';accuracy.style.left=x+'px';accuracy.style.top=y+'px';const mpp=Math.max(.1,Math.cos(user.lat*Math.PI/180)*2*Math.PI*6378137/world());const diameter=Math.max(22,Math.min(520,2*user.accuracy/mpp));accuracy.style.width=diameter+'px';accuracy.style.height=diameter+'px'}function project(lat,lon){return{x:(lon+180)/360*world(),y:(1-Math.log(Math.tan(lat*Math.PI/180)+1/Math.cos(lat*Math.PI/180))/Math.PI)/2*world()}}
        function renderTransit(){transit.replaceChildren();if(!transitEnabled||!transitData)return;const w=map.clientWidth,h=map.clientHeight,c=px(),ns='http://www.w3.org/2000/svg';transit.setAttribute('viewBox','0 0 '+w+' '+h);const xy=p=>{const q=project(p[0],p[1]);return{x:w/2+q.x-c.x,y:h/2+q.y-c.y}},color=line=>{let hash=0;for(let i=0;i<line.length;i++)hash=(hash*31+line.charCodeAt(i))|0;return ['#d88962','#77bba7','#e1b968','#8ca9dc','#c88fae'][Math.abs(hash)%5]};(transitData.tracks||[]).forEach(t=>{const points=t.points||[];if(points.length<2)return;const path=document.createElementNS(ns,'polyline');path.setAttribute('points',points.map(p=>{const q=xy(p);return q.x.toFixed(1)+','+q.y.toFixed(1)}).join(' '));path.setAttribute('stroke',color(t.line));path.setAttribute('class','transit-track');transit.appendChild(path);const flow=path.cloneNode();flow.setAttribute('class','transit-flow');transit.appendChild(flow)});(transitData.stops||[]).forEach(s=>{const p=xy([s.lat,s.lon]);if(p.x<-20||p.y<-20||p.x>w+20||p.y>h+20)return;const group=document.createElementNS(ns,'g'),dot=document.createElementNS(ns,'circle'),title=document.createElementNS(ns,'title');dot.setAttribute('cx',p.x);dot.setAttribute('cy',p.y);dot.setAttribute('r',4.2);dot.setAttribute('class','wl-stop');title.textContent=s.name+' · '+s.summary;group.appendChild(dot);group.appendChild(title);if(z>=13){const text=document.createElementNS(ns,'text');text.setAttribute('x',p.x+7);text.setAttribute('y',p.y-6);text.setAttribute('class','wl-label');text.textContent=(s.lines||[]).slice(0,3).map(l=>l.name+(l.eta>=0?' '+l.eta+'′':'')).join(' · ')||s.name;group.appendChild(text)}transit.appendChild(group)})}window.setTransitEnabled=v=>{transitEnabled=v;renderTransit()};window.setTransitDataEncoded=v=>{try{transitData=JSON.parse(decodeURIComponent(escape(atob(v))));renderTransit()}catch(e){}};
        function zoom(d){z=Math.max(4,Math.min(18,z+d));render();HomeLauncher.reportViewport(center.lat,center.lon,z)}window.setDarkMap=v=>{dark=v;document.body.classList.toggle('dark',v)};window.setCurrentLocation=(lat,lon,meters)=>{user={lat,lon,accuracy:meters||0};center={lat,lon};z=user.accuracy>1500?12:user.accuracy>400?14:16;render();HomeLauncher.reportViewport(center.lat,center.lon,z)};
        function dist(a,b){return Math.hypot(a.x-b.x,a.y-b.y)}function midpoint(a,b){return{x:(a.x+b.x)/2,y:(a.y+b.y)/2}}
        map.addEventListener('pointerdown',e=>{if(e.target.closest('#controls')||e.target.closest('#credit'))return;map.setPointerCapture(e.pointerId);pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});if(pointers.size===1)drag={x:e.clientX,y:e.clientY,c:px()};if(pointers.size===2){drag=null;const p=[...pointers.values()];pinch={distance:dist(p[0],p[1]),zoom:z}}});
        map.addEventListener('pointermove',e=>{if(!pointers.has(e.pointerId))return;pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});if(pointers.size>=2&&pinch){const p=[...pointers.values()],ratio=dist(p[0],p[1])/Math.max(1,pinch.distance),next=Math.max(4,Math.min(18,pinch.zoom+Math.round(Math.log2(ratio))));if(next!==z){z=next;render()}}else if(drag){tiles.style.transform='translate('+(e.clientX-drag.x)+'px,'+(e.clientY-drag.y)+'px)';positionMarker({x:drag.c.x-(e.clientX-drag.x),y:drag.c.y-(e.clientY-drag.y)})}});
        function endPointer(e){pointers.delete(e.pointerId);if(pointers.size===0){if(drag){const c=drag;fromPx(c.c.x-(e.clientX-c.x),c.c.y-(e.clientY-c.y));tiles.style.transform='';transit.style.transform='';render();HomeLauncher.reportViewport(center.lat,center.lon,z)}drag=null;pinch=null}else if(pointers.size===1){pinch=null;const p=[...pointers.values()][0];drag={x:p.x,y:p.y,c:px()}}}map.addEventListener('pointerup',endPointer);map.addEventListener('pointercancel',endPointer);
        map.addEventListener('wheel',e=>{e.preventDefault();zoom(e.deltaY<0?1:-1)},{passive:false});window.addEventListener('resize',render);render();setTimeout(()=>HomeLauncher.reportViewport(center.lat,center.lon,z),100);
        </script></body></html>"""

    private inner class AppsScreen : LinearLayout(this@MainActivity), Themed {
        private val title=label("Your apps",30f)
        private val count=label("",11f)
        private val addButton=label("＋  ADD APPS",11f)
        private val grid=GridLayout(this@MainActivity).apply{columnCount=4;alignmentMode=GridLayout.ALIGN_BOUNDS;useDefaultMargins=false}
        private val preferences=getSharedPreferences("launcher",MODE_PRIVATE)
        private val apps=packageManager.getInstalledApplications(0)
            .filter{packageManager.getLaunchIntentForPackage(it.packageName)!=null}
            .map{LaunchableApp(it,packageManager.getApplicationLabel(it).toString())}
            .sortedBy{it.name.lowercase(Locale.getDefault())}
        private val selected=preferences.getString("grid_apps","").orEmpty().split('|').filter{it.isNotBlank()}.toMutableList()
        private var activePalette=current
        init{
            orientation=VERTICAL
            setPadding(dp(24),dp(38),dp(24),dp(78))
            title.setPadding(0,0,0,dp(18));addView(title)
            val controls=LinearLayout(this@MainActivity).apply{gravity=Gravity.CENTER_VERTICAL;orientation=HORIZONTAL}
            controls.addView(count,LinearLayout.LayoutParams(0,-2,1f))
            addButton.setPadding(dp(12),dp(10),dp(12),dp(10));addButton.setOnClickListener{chooseApps()}
            controls.addView(addButton)
            addView(controls,LayoutParams(-1,-2))
            val scroll=ScrollView(this@MainActivity).apply{isFillViewport=true;addView(grid)}
            addView(scroll,LayoutParams(-1,0,1f))
            renderGrid()
        }
        override fun theme(p:Palette){
            activePalette=p;setBackgroundColor(p.bg);title.setTextColor(p.fg);count.setTextColor(p.muted)
            addButton.setTextColor(p.accent)
            renderGrid()
        }
        private fun saveSelection(){preferences.edit().putString("grid_apps",selected.joinToString("|")).apply()}
        private fun chooseApps(){
            if(apps.isEmpty())return
            val names=apps.map{it.name}.toTypedArray()
            val checked=BooleanArray(apps.size){apps[it].info.packageName in selected}
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Choose apps for your grid")
                .setMultiChoiceItems(names,checked){_,which,isChecked->checked[which]=isChecked}
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Save"){_,_->
                    val chosen=apps.filterIndexed{index,_->checked[index]}.map{it.info.packageName}
                    selected.clear();selected.addAll(chosen);saveSelection();renderGrid()
                }.show()
        }
        private fun editTile(app:LaunchableApp){
            val key="grid_size_${app.info.packageName}"
            val currentSize=preferences.getInt(key,1)
            AlertDialog.Builder(this@MainActivity).setTitle(app.name)
                .setItems(arrayOf(if(currentSize==1)"✓  1 × 1  Compact" else "1 × 1  Compact",if(currentSize==2)"✓  2 × 2  Large" else "2 × 2  Large","Remove from grid")){_,choice->
                    when(choice){0->preferences.edit().putInt(key,1).apply();1->preferences.edit().putInt(key,2).apply();2->{selected.remove(app.info.packageName);saveSelection()}}
                    renderGrid()
                }.show()
        }
        private fun renderGrid(){
            count.text="${selected.size}  ${if(selected.size==1)"APP" else "APPS"} ON YOUR BOARD"
            grid.removeAllViews();grid.columnCount=4;grid.rowCount=1
            val entries=selected.mapNotNull{pkg->apps.firstOrNull{it.info.packageName==pkg}}
            val placements=mutableListOf<GridPlacement>()
            val occupied=mutableListOf<MutableList<Boolean>>()
            fun ensureRows(last:Int){while(occupied.size<=last)occupied.add(MutableList(4){false})}
            entries.forEach{app->
                val span=if(preferences.getInt("grid_size_${app.info.packageName}",1)==2)2 else 1
                var placed=false
                search@ for(row in 0..200){ensureRows(row+span-1);for(column in 0..(4-span)){
                    var free=true
                    for(r in row until row+span)for(c in column until column+span)if(occupied[r][c])free=false
                    if(free){for(r in row until row+span)for(c in column until column+span)occupied[r][c]=true;placements.add(GridPlacement(app,row,column,span));placed=true;break@search}
                }}
                if(!placed)return@forEach
            }
            val screenWidthDp=(resources.displayMetrics.widthPixels/resources.displayMetrics.density).toInt()
            val available=screenWidthDp-48
            val cellWidth=(available-32)/4
            val addPlacement=firstOpenCell(occupied)
            // GridLayout validates child spans against rowCount as each view is added.
            // Set the final count first; otherwise switching a 1×1 tile to 2×2 can crash
            // while the previous render's smaller rowCount is still active.
            grid.rowCount=maxOf(1,occupied.size,addPlacement.first+1)
            placements.forEach{placement->
                val size=placement.span
                val tile=makeTile(placement.app,size)
                val params=GridLayout.LayoutParams(GridLayout.spec(placement.row,size),GridLayout.spec(placement.column,size)).apply{
                    width=dp(cellWidth*size+8*(size-1));height=dp(76*size+8*(size-1));setMargins(dp(4),dp(4),dp(4),dp(4))
                }
                tile.setOnClickListener{packageManager.getLaunchIntentForPackage(placement.app.info.packageName)?.let{startActivity(it)}}
                tile.setOnLongClickListener{editTile(placement.app);true}
                grid.addView(tile,params)
            }
            val add=makeAddTile()
            val addParams=GridLayout.LayoutParams(GridLayout.spec(addPlacement.first),GridLayout.spec(addPlacement.second)).apply{width=dp(cellWidth);height=dp(76);setMargins(dp(4),dp(4),dp(4),dp(4))}
            add.setOnClickListener{chooseApps()}
            grid.addView(add,addParams)
        }
        private fun firstOpenCell(occupied:List<List<Boolean>>):Pair<Int,Int>{
            for(row in 0..200)for(column in 0..3)if(row>=occupied.size||!occupied[row][column])return row to column
            return occupied.size to 0
        }
        private fun makeTile(app:LaunchableApp,size:Int):LinearLayout{
            val large=size==2
            val tile=LinearLayout(this@MainActivity).apply{
                orientation=VERTICAL;gravity=Gravity.CENTER
                background=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;cornerRadius=dp(if(large)20 else 16).toFloat();setColor(Color.argb(14,Color.red(activePalette.fg),Color.green(activePalette.fg),Color.blue(activePalette.fg)));setStroke(dp(1),Color.argb(38,Color.red(activePalette.muted),Color.green(activePalette.muted),Color.blue(activePalette.muted)))}
                isClickable=true;isFocusable=true;contentDescription="${app.name}, ${if(large)"2 by 2" else "1 by 1"} tile; long press to resize"
            }
            val icon=ImageView(this@MainActivity).apply{
                setImageDrawable(packageManager.getApplicationIcon(app.info));scaleType=ImageView.ScaleType.FIT_CENTER
                colorFilter=ColorMatrixColorFilter(ColorMatrix().apply{setSaturation(0f)})
            }
            val iconSize=if(large)dp(48) else dp(28)
            tile.addView(icon,LinearLayout.LayoutParams(iconSize,iconSize))
            val name=label(app.name,if(large)14f else 10f).apply{
                setTextColor(activePalette.fg);gravity=Gravity.CENTER;maxLines=if(large)2 else 1;ellipsize=android.text.TextUtils.TruncateAt.END
                setPadding(dp(3),dp(if(large)10 else 5),dp(3),0)
            }
            tile.addView(name,LinearLayout.LayoutParams(-1,-2))
            if(large){val hint=label("LONG-PRESS TO EDIT",8f).apply{setTextColor(activePalette.muted);setPadding(0,dp(7),0,0)};tile.addView(hint)}
            return tile
        }
        private fun makeAddTile():LinearLayout=LinearLayout(this@MainActivity).apply{
            orientation=VERTICAL;gravity=Gravity.CENTER
            background=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;cornerRadius=dp(16).toFloat();setColor(Color.argb(10,Color.red(activePalette.fg),Color.green(activePalette.fg),Color.blue(activePalette.fg)));setStroke(dp(1),Color.argb(64,Color.red(activePalette.accent),Color.green(activePalette.accent),Color.blue(activePalette.accent)))}
            addView(label("＋",26f).apply{setTextColor(activePalette.accent);gravity=Gravity.CENTER})
            addView(label("ADD",9f).apply{setTextColor(activePalette.muted);gravity=Gravity.CENTER})
            contentDescription="Add apps to grid"
        }
    }

    private fun label(s:String,size:Float)=TextView(this@MainActivity).apply{text=s;textSize=size;includeFontPadding=false;typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
    private fun dp(n:Int)= (n*resources.displayMetrics.density).toInt()
}

private class FloorPlanView(context:android.content.Context):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private var tint=TEXT_DARK;private var accent=0xffbd765c.toInt();private var progress=0f
    private val paths:List<Path> = buildList {
        add(Path().apply{moveTo(10f,10f);lineTo(310f,10f);lineTo(310f,210f);lineTo(10f,210f);close()})
        add(Path().apply{moveTo(10f,132f);lineTo(310f,132f)})
        add(Path().apply{moveTo(118f,10f);lineTo(118f,132f)})
        add(Path().apply{moveTo(214f,10f);lineTo(214f,132f)})
        add(Path().apply{moveTo(214f,132f);lineTo(214f,210f)})
        add(Path().apply{moveTo(10f,72f);lineTo(118f,72f)})
        add(Path().apply{moveTo(214f,74f);lineTo(310f,74f)})
        add(Path().apply{moveTo(44f,132f);lineTo(44f,210f)})
        add(Path().apply{moveTo(44f,166f);lineTo(118f,166f)})
        add(Path().apply{moveTo(260f,132f);lineTo(260f,210f)})
    }
    private val animator=ValueAnimator.ofFloat(0f,1f).apply{duration=42_000;repeatCount=ValueAnimator.INFINITE;interpolator=LinearInterpolator();addUpdateListener{progress=it.animatedValue as Float;invalidate()}}
    fun setTint(color:Int,activeColor:Int){tint=color;accent=activeColor;invalidate()}
    override fun onAttachedToWindow(){super.onAttachedToWindow();animator.start()}
    override fun onDetachedFromWindow(){animator.cancel();super.onDetachedFromWindow()}
    override fun onDraw(canvas:Canvas){super.onDraw(canvas);val s=maxOf(width*1.55f/320f,height*.52f/220f);if(s<=0)return;canvas.save();canvas.translate((width-320*s)/2f,height*.45f);canvas.scale(s,s)
        val drawPhase=.78f;val total=paths.size;val units=progress/drawPhase*total;val done=units.toInt().coerceIn(0,total);val fraction=(units-done).coerceIn(0f,1f)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1.45f;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND;paint.color=(tint and 0x00ffffff) or (24 shl 24)
        paths.forEach{canvas.drawPath(it,paint)}
        paint.strokeWidth=1.7f;paint.color=(tint and 0x00ffffff) or (58 shl 24)
        for(i in 0 until done)canvas.drawPath(paths[i],paint)
        var endpoint:FloatArray?=null
        if(done<total&&fraction>0){val path=paths[done];val measure=PathMeasure(path,false);val partial=Path();measure.getSegment(0f,measure.length*fraction,partial,true);paint.color=(accent and 0x00ffffff) or (190 shl 24);paint.strokeWidth=2.1f;canvas.drawPath(partial,paint);val pos=FloatArray(2);measure.getPosTan(measure.length*fraction,pos,null);endpoint=pos}
        endpoint?.let{paint.style=Paint.Style.FILL;paint.color=(accent and 0x00ffffff) or (220 shl 24);canvas.drawCircle(it[0],it[1],2.6f,paint)}
        canvas.restore()
    }
}

/** Tiny line-art weather vignette. Its motion runs only while Home is visible and animators are enabled. */
/** Full-screen ambient weather layer; kept deliberately softer than foreground text and controls. */
private class HomeWeatherScene(context:android.content.Context):View(context){
    private val density=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    private var tint=MUTED_DARK
    private var accent=0xffbd765c.toInt()
    private var kind=5
    private var severity=1
    private var phase=0f
    private var active=false
    private val motion=ValueAnimator.ofFloat(0f,1f).apply{
        duration=19_000;repeatCount=ValueAnimator.INFINITE;interpolator=LinearInterpolator()
        addUpdateListener{phase=it.animatedValue as Float;invalidate()}
    }
    fun setColors(primary:Int,highlight:Int){tint=primary;accent=highlight;invalidate()}
    fun setWeather(code:Int,gusts:Double){
        kind=when{code==0->if(gusts>=35)4 else 0;code in 1..3->if(gusts>=35)4 else 1;code==45||code==48->1;code in 51..67||code in 80..82||code in 95..99->2;code in 71..77||code in 85..86->3;else->1}
        severity=when(code){51,61,80,95->1;53,63,81,96->2;else->3};invalidate()
    }
    fun setActive(value:Boolean){active=value;syncMotion()}
    private fun syncMotion(){if(!isAttachedToWindow)return;val run=active&&ValueAnimator.areAnimatorsEnabled();if(run&&!motion.isStarted)motion.start() else if(!run&&motion.isStarted)motion.cancel()}
    override fun onAttachedToWindow(){super.onAttachedToWindow();syncMotion()}
    override fun onDetachedFromWindow(){motion.cancel();super.onDetachedFromWindow()}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas);if(width==0||height==0)return
        val w=width.toFloat();val h=height.toFloat();val top=h*.18f;val bottom=h*.91f
        canvas.save();canvas.clipRect(0f,top,w,h)
        when(kind){
            0->drawSun(canvas,w,h)
            1->{drawCloud(canvas,w*.10f+((phase*45f*density)%(w*.38f+120f*density))-55f*density,h*.16f,1.28f*density);drawCloud(canvas,w*.54f-((phase*28f*density)%(w*.32f+100f*density)),h*.21f,.92f*density)}
            2->{val x1=w*.05f+((phase*25f*density)%(w*.12f+80f*density));val y1=h*.16f;val s1=1.72f*density
                val x2=w*.49f-((phase*18f*density)%(w*.10f+65f*density));val y2=h*.21f;val s2=1.48f*density
                drawRainFromCloud(canvas,x1,y1,s1,bottom,0);drawRainFromCloud(canvas,x2,y2,s2,bottom,1)
                drawCloud(canvas,x1,y1,s1);drawCloud(canvas,x2,y2,s2)}
            3->{drawCloud(canvas,w*.10f+((phase*25f*density)%(w*.38f+100f*density))-55f*density,h*.16f,1.2f*density);drawSnow(canvas,w,top,bottom)}
            4->drawWind(canvas,w,h,top,bottom)
            5->{val drift=phase*22f*density;drawCloud(canvas,w*.13f+(drift%(w*.16f+95f*density))-35f*density,h*.16f,1.55f*density);drawCloud(canvas,w*.55f-(drift*.65f%(w*.12f+70f*density)),h*.21f,1.12f*density)}
        }
        drawLandscape(canvas,w,h)
        canvas.restore()
    }
    private fun drawSun(c:Canvas,w:Float,h:Float){
        val x=w*.76f;val y=h*.51f;val r=42f*density
        paint.style=Paint.Style.FILL;paint.color=(accent and 0x00ffffff) or (5 shl 24);c.drawCircle(x,y,r*2.2f,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1.4f*density;paint.color=(accent and 0x00ffffff) or (38 shl 24)
        for(i in 0..11){val a=i*Math.PI/6.0;val inner=r*1.22f;val outer=r*(1.48f+0.05f*kotlin.math.sin(phase*2*Math.PI).toFloat());c.drawLine(x+kotlin.math.cos(a).toFloat()*inner,y+kotlin.math.sin(a).toFloat()*inner,x+kotlin.math.cos(a).toFloat()*outer,y+kotlin.math.sin(a).toFloat()*outer,paint)}
        paint.color=(accent and 0x00ffffff) or (38 shl 24);c.drawCircle(x,y,r,paint)
    }
    private fun drawCloud(c:Canvas,x:Float,y:Float,scale:Float){
        c.save();c.translate(x,y);c.scale(scale,scale)
        val p=Path().apply{moveTo(5f,39f);cubicTo(-2f,34f,1f,22f,12f,20f);cubicTo(15f,4f,37f,1f,46f,16f);cubicTo(59f,7f,78f,14f,78f,28f);cubicTo(99f,27f,102f,42f,87f,45f);lineTo(13f,45f);close()}
        paint.style=Paint.Style.FILL;paint.color=(tint and 0x00ffffff) or (11 shl 24);c.drawPath(p,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1.25f;paint.color=(tint and 0x00ffffff) or (38 shl 24);c.drawPath(p,paint);c.restore()
    }
    private fun drawRainFromCloud(c:Canvas,cloudX:Float,cloudY:Float,scale:Float,bottom:Float,seed:Int){
        val count=if(severity==1)40 else if(severity==2)68 else 96
        val left=cloudX+10f*scale;val span=78f*scale;val start=cloudY+39f*scale
        paint.strokeWidth=.9f*density
        for(i in 0 until count){
            val x=left+((i*47+seed*31)%101)/100f*span
            val base=((i*61+seed*43)%97)/97f
            val travel=(phase*(.35f+(i%4)*.045f)+base)%1f
            val y=start+travel*(bottom-start);val len=(7f+(i%5)*2f)*density
            paint.color=((if(i%6==0)accent else tint) and 0x00ffffff) or ((if(i%6==0)58 else 42) shl 24)
            c.drawLine(x,y,x-1.5f*density,y+len,paint)
        }
    }
    private fun drawSnow(c:Canvas,w:Float,top:Float,bottom:Float){
        paint.style=Paint.Style.FILL
        for(i in 0 until 62){val base=((i*137)%997)/997f;val travel=(phase*(.12f+(i%4)*.035f)+base)%1f;val x=(((i*83)%991)/991f*w)+kotlin.math.sin((phase*2*Math.PI+i).toDouble()).toFloat()*5f*density;val y=top+travel*(bottom-top);paint.color=(if(i%5==0)accent else tint) and 0x00ffffff or ((if(i%5==0)34 else 18) shl 24);c.drawCircle(x,y,(.7f+(i%3)*.35f)*density,paint)}
        paint.style=Paint.Style.STROKE
    }
    private fun drawWind(c:Canvas,w:Float,h:Float,top:Float,bottom:Float){
        paint.strokeWidth=1.1f*density
        for(i in 0..6){val y=top+(bottom-top)*(i+1)/9f;val offset=(phase*95f*density+i*47f*density)%(w+170f*density);val x=w-offset
            val p=Path().apply{moveTo(x,y);cubicTo(x+w*.20f,y-28f*density,x+w*.34f,y+24f*density,x+w*.55f,y-2f*density);cubicTo(x+w*.68f,y-18f*density,x+w*.78f,y+14f*density,x+w*.94f,y)}
            paint.color=(if(i%3==0)accent else tint) and 0x00ffffff or ((if(i%3==0)20 else 13) shl 24);c.drawPath(p,paint)
        }
    }
    private fun drawLandscape(c:Canvas,w:Float,h:Float){
        val ridge=Path().apply{moveTo(0f,h*.84f);cubicTo(w*.18f,h*.77f,w*.25f,h*.80f,w*.43f,h*.85f);cubicTo(w*.63f,h*.91f,w*.78f,h*.79f,w,h*.83f);lineTo(w,h);lineTo(0f,h);close()}
        paint.style=Paint.Style.FILL;paint.color=(tint and 0x00ffffff) or (20 shl 24);c.drawPath(ridge,paint)
        val near=Path().apply{moveTo(0f,h*.91f);cubicTo(w*.20f,h*.86f,w*.34f,h*.93f,w*.54f,h*.90f);cubicTo(w*.75f,h*.86f,w*.84f,h*.89f,w,h*.87f);lineTo(w,h);lineTo(0f,h);close()}
        paint.color=(accent and 0x00ffffff) or (15 shl 24);c.drawPath(near,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1.1f*density;paint.color=(tint and 0x00ffffff) or (32 shl 24)
        c.drawPath(Path().apply{moveTo(0f,h*.84f);cubicTo(w*.18f,h*.77f,w*.25f,h*.80f,w*.43f,h*.85f);cubicTo(w*.63f,h*.91f,w*.78f,h*.79f,w,h*.83f)},paint)
        paint.style=Paint.Style.STROKE
    }
}

private class WeatherMotionView(context:android.content.Context):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    private var tint=MUTED_DARK
    private var accent=0xffbd765c.toInt()
    private var kind=5 // no weather data yet
    private var phase=0f
    private var active=false
    private val motion=ValueAnimator.ofFloat(0f,1f).apply{
        duration=6800;repeatCount=ValueAnimator.INFINITE;interpolator=LinearInterpolator()
        addUpdateListener{phase=it.animatedValue as Float;invalidate()}
    }
    fun setColors(primary:Int,highlight:Int){tint=primary;accent=highlight;invalidate()}
    fun setWeather(code:Int,gusts:Double){kind=when{code==0->if(gusts>=35)4 else 0;code in 1..3->if(gusts>=35)4 else 1;code==45||code==48->1;code in 51..67||code in 80..82||code in 95..99->2;code in 71..77||code in 85..86->3;else->1};invalidate()}
    fun setActive(value:Boolean){active=value;syncMotion()}
    private fun syncMotion(){if(!isAttachedToWindow)return;val shouldRun=active&&ValueAnimator.areAnimatorsEnabled();if(shouldRun&&!motion.isStarted)motion.start() else if(!shouldRun&&motion.isStarted)motion.cancel()}
    override fun onAttachedToWindow(){super.onAttachedToWindow();syncMotion()}
    override fun onDetachedFromWindow(){motion.cancel();super.onDetachedFromWindow()}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas);val scale=minOf(width,height)/48f;if(scale<=0)return
        canvas.save();canvas.translate((width-48*scale)/2f,(height-48*scale)/2f);canvas.scale(scale,scale)
        paint.strokeWidth=1.35f;paint.color=(tint and 0x00ffffff) or (142 shl 24)
        when(kind){
            0->drawSun(canvas)
            1->{canvas.save();canvas.translate((phase-.5f)*3f,0f);drawCloud(canvas);canvas.restore()}
            2->{drawCloud(canvas);paint.color=(accent and 0x00ffffff) or (158 shl 24);paint.strokeWidth=1.25f
                for(i in 0..3){val x=13f+i*7f;val y=31f+((phase*22f+i*8f)%12f);canvas.drawLine(x,y,x-2f,y+4f,paint)} }
            3->{drawCloud(canvas);paint.style=Paint.Style.FILL;paint.color=(accent and 0x00ffffff) or (170 shl 24)
                for(i in 0..4){val x=10f+(i*9f+phase*5f)%31f;val y=34f+((i*7f+phase*13f)%10f);canvas.drawCircle(x,y,1.25f,paint);canvas.drawCircle(x+2.2f,y+2f,0.8f,paint)};paint.style=Paint.Style.STROKE }
            4->{paint.color=(tint and 0x00ffffff) or (132 shl 24);for(i in 0..2){val y=14f+i*10f;val drift=phase*9f+i*4f;val path=Path().apply{moveTo(8f-drift%7f,y);cubicTo(18f,y-5f,26f,y+5f,37f,y);quadTo(41f,y-1f,39f,y-3f)};canvas.drawPath(path,paint)} }
        }
        canvas.restore()
    }
    private fun drawSun(canvas:Canvas){
        val bob=kotlin.math.sin(phase*2.0*Math.PI).toFloat()*1.2f
        paint.color=(accent and 0x00ffffff) or (150 shl 24);paint.strokeWidth=1.25f
        for(i in 0..7){val angle=i*Math.PI/4.0;val inner=13f;val outer=17f;canvas.drawLine(24f+kotlin.math.cos(angle).toFloat()*inner,24f+bob+kotlin.math.sin(angle).toFloat()*inner,24f+kotlin.math.cos(angle).toFloat()*outer,24f+bob+kotlin.math.sin(angle).toFloat()*outer,paint)}
        paint.color=(accent and 0x00ffffff) or (190 shl 24);canvas.drawCircle(24f,24f+bob,8f,paint)
    }
    private fun drawCloud(canvas:Canvas){
        val path=Path().apply{moveTo(10f,29f);cubicTo(6f,29f,7f,22f,12f,21f);cubicTo(13f,14f,22f,13f,26f,19f);cubicTo(32f,16f,38f,20f,37f,25f);cubicTo(41f,29f,38f,32f,34f,32f);lineTo(13f,32f);close()}
        paint.style=Paint.Style.FILL;paint.color=(tint and 0x00ffffff) or (15 shl 24);canvas.drawPath(path,paint)
        paint.style=Paint.Style.STROKE;paint.color=(tint and 0x00ffffff) or (150 shl 24);canvas.drawPath(path,paint)
    }
}
