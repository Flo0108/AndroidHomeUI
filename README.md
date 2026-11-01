# HomeLauncher

A minimal Android home launcher with a weather-driven home screen, a manually curated app grid, and a map page.

## Build

On Windows, run `gradlew.bat assembleDebug`. The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Map and transit

The map uses OpenStreetMap tiles and the official Wiener Linien open-data feed. Enable or pause the `WIENER LINIEN · LIVE` layer on the map; live requests are batched for up to ten nearby platforms. The refresh interval is configurable from 20 to 120 seconds, with a 15-second minimum spacing guard. Static platform and route geometry is bundled from Wiener Linien OGD files.

The public feed returns stop-level departures, not individual vehicle GPS positions. Animated line flow uses the official route geometry and currently active departure lines; it is a visual service-flow estimate, not exact vehicle tracking. Wiener Linien data is credited in-app under CC BY 4.0. OpenStreetMap attribution is also shown in-app.
