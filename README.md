# Cosy TV: a debloated Sony Google TV with a weather-aware home screen

My living-room Sony TV, debloated and given a calm, custom home screen that shows the real sun and weather outside, plus everything I need to set it up again or put it back to stock.

> **Primarily for my personal use.** This repository is my backup and setup/restore reference for my Sony XR-65A80J and PC. Keeping everything needed to rebuild my launcher, repeat my setup or restore my TV is its main purpose.

| Before: stock Google TV home | After: Cosy Home |
|---|---|
| ![Before](before.png) | ![After](after.png) |

> **No warranty, use at your own risk.** The setup scripts, package lists, keycodes, paths and settings are specific to my TV (EU firmware) and PC. Disabling the wrong system package can break a TV; the [cleanyourtv](https://github.com/Gathin23/cleanyourtv) project is the place to start for your own model. The launcher source can be reused separately, as described below.

## What this does

- **TV:** Sony XR-65A80J (65" OLED, Google TV / Android 12) with a Sony Bravia Theatre Bar 8 on HDMI eARC.
- **Debloat:** 38 packages disabled (not uninstalled) plus the Google TV launcher. Removed: Samba TV's on-screen content tracking, Sony and Google telemetry, Sony Select and Sony-tab ads, shop demo, help apps, hotel mode, AirPlay/DLNA/HbbTV, BRAVIA CAM apps and leftover setup apps. Kept: TV, HDMI, CEC/eARC, the remote, voice, Chromecast, Miracast, Sony updates and all streaming apps. List: [debloat/debloat.txt](debloat/debloat.txt).
- **Ad-blocking DNS:** Private DNS set to AdGuard (`dns.adguard-dns.com`), so ads and trackers are blocked in every app (not YouTube ads).
- **New home screen (Cosy Home):**
  - a valley scene that follows the real sun **and the real weather** (sun, clouds, rain, snow, fog, thunderstorm);
  - a big clock, the date and the weather line in the TV's language;
  - a **now playing** card with media controls;
  - status icons top right (inputs, network, settings);
  - the moon in its real phase, only while it is above the horizon;
  - a centred dock of up to 7 apps with a glow on the focused app, editable on the TV (hold OK on a tile);
  - an apps and inputs panel.
- **Remote:** the **YouTube** button opens **SmartTube**; the other buttons work as before.
- **OLED care:** Google's Ambient mode screensaver (moving photos) after 5 idle minutes, plus the TV's own Pixel Shift and Panel Refresh.
- **Backup and restore:** the TV state before any change is in [backup/](backup/) (device IDs redacted), and `scripts\restore.ps1 -RemoveLauncher` puts the TV back to stock from this repo alone.

## Built with

| Project | What was used | Licence |
|---|---|---|
| [cleanyourtv](https://github.com/Gathin23/cleanyourtv) by Gathin | The method and prompt, helper tools (`tools/`), troubleshooting docs, the Sony debloat reference lists | MIT |
| [cosy-home-tv](https://github.com/oliverjwroberts/cosy-home-tv) by Oliver Roberts | The base of the launcher (scene, clock, dock, apps panel) | MIT |
| [CleanXiaomiTV](https://github.com/majnioui/CleanXiaomiTV) | AdGuard DNS idea, the media session reader behind "now playing" | MIT |
| [Outfit](https://github.com/Outfitio/Outfit-Fonts) | The launcher's typeface | OFL 1.1 |
| [Open-Meteo](https://open-meteo.com) | Weather data (no account, no key) | CC BY 4.0 |

Licence files: [licenses/](licenses/). My own parts: [MIT](LICENSE). The setup itself was done step by step with an AI coding agent (Claude Code) on the real TV; the full prompt is in [docs/03-original-prompt.md](docs/03-original-prompt.md).

## Live weather

The scene is drawn in code (no image files) and follows sunrise and sunset for the configured town and the current weather from Open-Meteo, refreshed every 30 minutes. The weather line under the date and the picture always match.

| Dawn, fog | Day, partly cloudy | Day, snow |
|---|---|---|
| ![Dawn fog](screenshots/demo-live-weather-dawn-fog.png) | ![Day partly cloudy](screenshots/demo-live-weather-day-partly-cloudy.png) | ![Day snow](screenshots/demo-live-weather-day-snow.png) |
| **Dusk, rain** | **Night, thunderstorm** | **Night, clear** |
| ![Dusk rain](screenshots/demo-live-weather-dusk-rain.png) | ![Night thunderstorm](screenshots/demo-live-weather-night-thunderstorm.png) | ![Night clear](screenshots/demo-live-weather-night-clear.png) |

Every scene works at each time of day: **dawn** (40 min before to 50 min after sunrise), **day**, **dusk** (50 min before to 40 min after sunset) and **night**.

| Scene | Open-Meteo (WMO) codes | Weather line (fi / en) | What the picture does |
|---|---|---|---|
| Clear | 0 | Selkeää / Clear | No clouds; sun or the moon in its phase; full stars at night |
| Partly cloudy | 1, 2 | Enimmäkseen selkeää, Puolipilvistä / Mostly clear, Partly cloudy | Drifting white clouds by day; stars dimmed at night |
| Cloudy | 3 | Pilvistä / Overcast | Grey sky, extra cloud layer, sun only a faint glow, no moon or stars |
| Fog | 45, 48 | Sumua / Fog | Washed-out sky, thick fog banks over the valley, faint clouds |
| Rain | 51-57, 61-67, 80-82 | Tihkusadetta, Heikkoa sadetta, Sadetta, Rankkasadetta, Sadekuuroja ... | Grey sky, grey clouds, falling rain in four strengths |
| Snow | 71-77, 85, 86 | Lumisadetta, Lumikuuroja / Snow, Snow showers | Grey-white sky, snowy hills, drifting snowflakes in three strengths |
| Thunderstorm | 95, 96, 99 | Ukkosta, Ukkosta ja raekuuroja / Thunderstorm (and hail) | Dark sky, heavy rain, a double lightning flash every 11 s |

Preview any scene (`auto` goes back to live; `temp` and `clock` only change the text, for demo screenshots):

```powershell
adb shell am start -n com.cosyhome.tv/.HomeActivity --es phase dusk --es weather rain --ei temp 7 --es clock 17:48
adb shell am start -n com.cosyhome.tv/.HomeActivity --es phase auto --es weather auto
```

`scripts\tv-demo.ps1` plays a live demo on the TV (13 scenes, 8 s each; `-Seconds`, `-Loop`), and `scripts\weather-demo.ps1` retakes the demo screenshots.

## Now playing

![Now playing](screenshots/demo-now-playing.png)

When music plays in any app (Spotify, SmartTube, YouTube Music ...), a small card under the weather line shows the cover, title and artist with previous / play-pause / next. **Up** from the dock goes to play-pause, **OK** on the cover opens the app's player, **Down** goes back to the dock. It needs notification access, which is only used to read media sessions, never notifications (granted by `scripts\launcher-install.ps1`).

## Change the launcher

Current version: **1.1.0** ([versions](launcher/README.md#versions)). Settings: [launcher/cosy.properties](launcher/cosy.properties). After a change: `scripts\launcher-install.ps1`. More in [launcher/README.md](launcher/README.md).

- **Dock order:** `dock=` (package names, at most seven, TV always first). The apps list starts with the same apps, then `pinned=`, then the rest alphabetically.
- **On the TV:** hold OK on a tile for its menu: Add to / Remove from dock, App info, Uninstall. A dock edited on the TV is kept until `dock=` changes; `scripts\backup.ps1` saves it as `launcher-dock.txt`, so it can be copied into `dock=`.
- **Hidden tiles:** `hidden=` (apps stay installed).
- **Location:** `location.name`, `location.latitude`, `location.longitude`.
- **Temperature:** `weather.temperature_unit=fahrenheit` for °F (default Celsius).
- **Scene:** [SceneView.java](launcher/app/src/main/java/com/cosyhome/tv/SceneView.java), with palettes per time of day and the weather effects.
- **Remote buttons:** `app_keys=196:org.smarttube.stable` (YouTube button -> SmartTube), through the launcher's accessibility service.

## Use the launcher on another TV

You are welcome to **fork, build, modify and reuse the Cosy Home launcher**. The source is in [launcher/](launcher/), with its own Gradle project and configurable location, apps, input shortcuts and remote-button mappings. The code is MIT-licensed; retain the copyright and licence notices, including the [third-party notices](licenses/).

It is prepared for customisation at the source and configuration level, and requires Android 8.0 (API 26) or newer. This fork has only been tested on my Sony XR-65A80J, so compatibility with other TVs is unverified. Adapt [cosy.properties](launcher/cosy.properties) for your device and build the launcher separately; the PowerShell installer, debloat list and restore scripts describe my setup and need review before use on another TV. See [the launcher documentation](launcher/README.md) for the project and build requirements.

## Re-apply from scratch

For the same Sony TV after a reset, run these from the repo root:

1. Install the [PC tools](docs/01-prerequisites.md), enable [TV debugging](docs/02-tv-first-setup.md), connect to `192.168.8.202:5555`, and accept the authorisation prompt.
2. Save a fresh baseline with `scripts\backup.ps1 -Label before-debloat`. Keep it for rollback; `scripts\restore.ps1 -Baseline <backup-folder>` selects it instead of the original baseline.
3. Reinstall apps from [their sources](docs/05-apps.md). No APK files are stored here.
4. Review [debloat/debloat.txt](debloat/debloat.txt) against the current firmware. Check the saved Wi-Fi owner before disabling setupwraith (see [the checklist](docs/00-plan.md)). Apply batches 1-7 with `scripts\disable.ps1 debloat\debloat.txt -Batch 1`, changing the batch number each time. Check TV, HDMI, sound and streaming between batches, then reboot and check again. The script refuses any list that names Android's core packages or the TV middleware (permission controller, package installer, system UI, settings, most `com.android.providers.*`, fused location, `com.mediatek.tis` ...), because disabling them can leave the TV unable to boot. It also skips, and names, setupwraith while it owns the saved Wi-Fi, the app that owns Home while nothing else can take over, packages that drive the HDMI/AV inputs, and the apps that open the inputs and Settings; each has a one-run override switch listed at the top of the script. Afterwards it reads back what is still enabled. These checks follow upstream cleanyourtv v3.4.0.
5. Run `scripts\dns.ps1` to enable AdGuard DNS, and check streaming still works.
6. Review [launcher/cosy.properties](launcher/cosy.properties), then run `scripts\launcher-install.ps1 -TakeHome`. It builds and installs the saved launcher, disables setupwraith and the stock launcher, and enables the remote-button service and media access. Verify Home and each physical remote button.
7. Select Google Ambient mode as the screensaver on the TV. Run `adb shell settings put system screen_off_timeout 300000` for the saved 5-minute delay and `adb shell settings put global stay_on_while_plugged_in 0` so Stay awake cannot prevent it. Check the screensaver starts while idle.
8. Save the result with `scripts\backup.ps1 -Label after-setup`, then [close ADB](docs/04-close-adb.md).

## Known quirks

Side effects of running without the Google TV launcher (`launcherx`) and the setup wizard (`setupwraith`). None of them breaks anything in daily use.

- **Down in Ambient mode said "Sisällön lataaminen epäonnistui" / "Couldn't load content".** Down opens Ambient mode's content panel, which loads from `launcherx`. Since launcher 1.0.2 the remote-button service turns Down into Back while Ambient mode is showing, so it closes the screensaver like every other key.
- **Ambient mode > Google Photos > Select albums closes at once or says "Unable to load content".** The album picker is `launcherx`'s and also needs `setupwraith`; the slideshow itself keeps playing. To change the albums: `adb shell pm enable com.google.android.apps.tv.launcherx` and `adb shell pm enable com.google.android.tungsten.setupwraith`, then `adb shell am force-stop com.google.android.apps.tv.launcherx`, pick the albums under Settings > Screen saver > Ambient mode, then disable both again (`scripts\disable.ps1 debloat\debloat.txt`) and give Home back to Cosy Home (`scripts\launcher-install.ps1 -TakeHome`). While `launcherx` is on it owns Home. Reported upstream on a Sony XR-77A80L with this firmware build ([TROUBLESHOOTING](docs/upstream/TROUBLESHOOTING.md)).
- **logcat repeats `Failed to find provider info for com.google.android.tungsten.setupwraith` about twice a second.** Sony's `com.sony.dtv.sonysystemservice` polls the disabled wizard every 500 ms, roughly 7,000 lines an hour. Harmless: nothing on screen changes and idle CPU still dropped after the debloat. Turning the wizard back on is worse, as its recovery screen takes Home. Leave it ([cleanyourtv #2853](https://github.com/Gathin23/cleanyourtv/issues/2853), seen on a Sony XR-77A80L; check this TV with `adb logcat -d | findstr setupwraith`).

## After a Sony firmware update

An update can re-enable disabled packages, give Home back to the Google TV launcher, switch off the remote-button service and add new preloads. Enable debugging, connect, and run `scripts\check.ps1`. It only reads: it lists what drifted (exit code 1) and any packages not in the after-setup backup, and names the script that fixes each item (`disable.ps1`, `launcher-install.ps1 -TakeHome`, `dns.ps1`). Review new packages before adding them to the list, then [close ADB](docs/04-close-adb.md) again.

## Undo / restore

| What | How |
|---|---|
| Re-enable one app | `adb shell pm enable <package>` (names in [debloat/debloat.txt](debloat/debloat.txt)) |
| Everything back to stock | `scripts\restore.ps1 -RemoveLauncher`, then `adb reboot`. Re-enables everything this setup disabled (not what Sony disabled at the factory), the Google TV launcher and setup wizard if the baseline had them on, puts Private DNS and the screensaver delay back, and removes Cosy Home with its services. |
| AdGuard DNS off | `scripts\dns.ps1 -Off` (= `settings put global private_dns_mode opportunistic`) |
| Before a factory reset | Run `scripts\restore.ps1` first (the setup wizard must be enabled) |

## Repo layout

```
before.png, after.png  the home screen before and after
backup/        TV state before any change (packages, settings, getprop; device IDs redacted)
debloat/       the debloat list, the log of what was disabled, and the Sony reference list
docs/          setup checklist, PC and TV setup, the original prompt, closing ADB, apps list
docs/upstream/ troubleshooting and remote-help docs from cleanyourtv v3.4.0 (links inside point to that repo)
launcher/      Cosy Home launcher source and its settings
licenses/      third-party licences
scripts/       PowerShell scripts for this TV: backup, disable, check, restore, DNS, launcher install, screenshots
screenshots/   demo screenshots
tools/         helper scripts from cleanyourtv
```

| Doc | Contents |
|---|---|
| [docs/00-plan.md](docs/00-plan.md) | The plan |
| [docs/01-prerequisites.md](docs/01-prerequisites.md) | PC tools: adb, Android SDK, JDK |
| [docs/02-tv-first-setup.md](docs/02-tv-first-setup.md) | Developer mode and debugging on the TV (Finnish menu paths) |
| [docs/03-original-prompt.md](docs/03-original-prompt.md) | The full original prompt, answers, and how to run it again |
| [docs/04-close-adb.md](docs/04-close-adb.md) | Switch debugging off and check port 5555 is closed |
| [docs/05-apps.md](docs/05-apps.md) | Installed apps, versions and sources |
