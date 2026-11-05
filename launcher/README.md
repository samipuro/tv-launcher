# Cosy Home (tv-launcher fork)

The Android TV home screen used on this TV. It is a fork of [cosy-home-tv](https://github.com/oliverjwroberts/cosy-home-tv) by Oliver Roberts, which itself started from the launcher in [cleanyourtv](https://github.com/Gathin23/cleanyourtv) by Gathin. The media session reader for "now playing" is ported from [CleanXiaomiTV](https://github.com/majnioui/CleanXiaomiTV). All MIT; licences in [../licenses](../licenses).

## Changes in this fork

- **Live weather in the scene:** clouds, rain, snow, fog and thunderstorm from the Open-Meteo weather code, at every time of day (`SceneView.setWeather`). Preview with `--es weather <name>`.
- **Now playing card:** cover, title, artist, previous / play-pause / next for any media app (`NowPlaying.java`, `MediaListener.java`).
- **Status icons top right:** inputs, network (ethernet / Wi-Fi / offline) and settings.
- **Remote button remap:** `app_keys=<keycode>:<package>`, through an accessibility key filter (`KeyService.java`). Here the YouTube button (keycode 196) opens SmartTube.
- **Down closes the screensaver:** in Google Ambient mode, Down would open a content panel that loads from the disabled Google TV home and only shows "Couldn't load content". The same key filter turns it into Back, so Down closes the screensaver like every other key.
- **Finnish translation** (`values-fi`), and the time is formatted in the TV language's own style (14.12 in Finnish, 14:12 in English).
- **Dock:** centred, TV first, a glow in the app's own colour on the focused tile; apps-grid tiles get a crisp border instead.
- **Sony settings:** Live TV `com.sony.dtv.tvx`, USB `com.sony.dtv.smartmediaapp`, no Ambilight chip.
- **The real moon:** at night the moon shows its real phase (lit on the right while waxing, on the left while waning, nothing at new moon) and is only drawn while it is above the horizon at `location.*` (`Moon.java`, offline, about a degree: a few minutes on moonrise and moonset). With no location set it is always shown. Preview with `--es moon <new|crescent|first|gibbous|full|last|waning>` (`auto` goes back to live).
- **Temperature unit:** `weather.temperature_unit=fahrenheit` shows °F; anything else is Celsius. The reading is stored in Celsius and converted when shown, so switching needs no new fetch.
- **From upstream cosy-home-tv (Oct 2026):** the moon's crescent is cut out of the disc instead of painted over in sky colour; banners beyond the dock and first grid row are decoded on a background thread and cached until the app updates; tiles and input chips are only rebuilt when apps or inputs changed; the package receiver is registered once. Gradle wrapper 9.7.1.
- Removed: the per-minute pixel shift (the TV's screensaver and panel protection handle OLED care).

## Build and install

Settings are in [cosy.properties](cosy.properties) (defaults in [cosy.defaults.properties](cosy.defaults.properties)). Needs JDK 17+ and the Android SDK (platform 34, build-tools 34).

```powershell
scripts\launcher-install.ps1            # build, install, switch the remote-button service and media access back on
scripts\launcher-install.ps1 -TakeHome  # first time: also make it the Home screen and disable the Google TV launcher
```

Preview a scene: `adb shell am start -n com.cosyhome.tv/.HomeActivity --es phase night --es weather clear --es moon full` (`auto` for each goes back to live).
