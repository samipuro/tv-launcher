# Apps

Apps on the TV on 2026-10-21 that are not part of the firmware, and where to get them again. No APK files are kept in this repo; everything installs from its own source.

| App | Package | Version (code) | Source |
|---|---|---|---|
| APKMirror Installer | `com.apkmirror.helper.prod` | 2.0.3 (41) | Play Store (beta listing); older versions on [APKMirror](https://www.apkmirror.com/uploads/?appcategory=apkmirror-installer-official) |
| F1 TV | `com.formulaone.production` | 3.0.50-SP168.9.0 (30500001) | Play Store (on this TV); also [APKMirror](https://www.apkmirror.com/apk/formula-one-digital-media-limited/f1-tv-android-tv/) |
| SmartTube | `org.smarttube.stable` | 32.68 (2458) | [GitHub releases](https://github.com/yuliskov/SmartTube/releases) (sideloaded) |
| Send files to TV | `com.yablio.sendfilestotv` | 1.4.22 (69) | Play Store |
| File Manager+ | `com.alphainventor.filemanager` | 3.8.3 (2103083) | Play Store |
| Spotify | `com.spotify.tv.android` | 1.134.2 | Play Store |
| Yle Areena | `com.yle.webtv` | 17.9.1 | Play Store |
| Ruutu | `com.thirdpresence.ruutu` | 8.28.2 | Play Store |
| MTV Katsomo | `fi.mtvkatsomo` | 11.41.1 | Play Store |
| Elisa Viihde | `com.elisa.viihde.tv` | 2.0.2.100 | Play Store |
| Viaplay | `com.viaplay.android` | 26.41.4 | Play Store |
| Disney+ | `com.disney.disneyplus` | 26.18.0 | Play Store |
| Old MTV app (disabled) | `fi.mtv.svod` | 1.0.56 | Play Store (obsolete) |
| Netflix, Prime Video, YouTube | preinstalled | - | Firmware / Play Store |
| Cosy Home (launcher) | `com.cosyhome.tv` | 1.1.0 (3) | Built from [../launcher](../launcher) |

## Installing an APK from the PC (fallback)

For an app that is not in the Play Store, with ADB switched on:

```powershell
scripts\install-apk.ps1 "$env:USERPROFILE\Downloads\<file>.apk"     # also .apkm / .apks / .xapk bundles or a folder of split APKs
```

The script unpacks bundles, installs all splits in one session and keeps the app's data. Switch ADB off again afterwards ([04-close-adb.md](04-close-adb.md)).
