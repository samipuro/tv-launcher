# PC prerequisites (Windows)

The PC is the Windows 11 machine that runs the setup. Tool installs go under `D:\Server\Tools\`.

| Tool | Version used | Where | Why |
|---|---|---|---|
| Android platform-tools (`adb`) | r37.0.1 | `D:\Server\Tools\platform-tools\` | Talk to the TV over the network |
| Android SDK command-line tools (`sdkmanager`) | cmdline-tools 23.0 | `D:\Server\Tools\Android\Sdk\cmdline-tools\latest\` | Install the SDK parts the launcher build needs |
| Android SDK platform 34 + build-tools 34 | 34 | `D:\Server\Tools\Android\Sdk\` | Build the launcher APK |
| JDK | 17 or newer (21 used) | system | Gradle |
| Git | with Git Credential Manager | system | Clone and push this repo |
| Git Bash | from Git for Windows (here Laragon's) | `D:\Laragon\bin\git\bin\bash.exe` | `launcher-install.ps1` runs the Gradle wrapper through it |
| Python | 3.13 | system | The `cleanyourtv` helper scripts (`adb-closed.py` etc.) |
| Python venv | `tools\requirements.txt` | `tools\venv\` | `tools\disable.ps1`, `tools\restore.ps1` |
| Launcher signing key | Android debug key | `%USERPROFILE%\.android\debug.keystore` | Updates must be signed with the installed launcher's key. Back it up outside this repo |

## Install

Downloads come from Google's SDK repository (`https://dl.google.com/android/repository/`). The SHA-1 of each zip was checked against `repository2-3.xml`.

```powershell
# platform-tools (adb)
#   https://dl.google.com/android/repository/platform-tools-latest-windows.zip
#   extract so that D:\Server\Tools\platform-tools\adb.exe exists

# SDK command-line tools
#   https://developer.android.com/studio#command-line-tools-only
#   extract the inner "cmdline-tools" folder to D:\Server\Tools\Android\Sdk\cmdline-tools\latest

$env:ANDROID_HOME = 'D:\Server\Tools\Android\Sdk'
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-34" "build-tools;34.0.0"

# Python venv for tools
python -m venv tools\venv
tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt
```

The first launcher build needs internet: the Gradle wrapper downloads Gradle 9.7.1 (about 150 MB) and the Android Gradle plugin.

The scripts in this repo do not need anything on `PATH`. They look for adb at `D:\Server\Tools\platform-tools\adb.exe` (override with `$env:ADB`), the SDK at `D:\Server\Tools\Android\Sdk` (`$env:ANDROID_HOME`), Git Bash at the path above (`$env:GIT_BASH`) and the TV at `192.168.8.202` (`$env:TV_HOST` or `-TvHost`).

## Signing key

The launcher is signed with the debug key in `%USERPROFILE%\.android\debug.keystore` (made by the first build, 2026-10-21). Android only installs an update over Cosy Home if it has the same key; with another key the launcher must be uninstalled first, which loses Home, its permissions and the dock edited on the TV. Keep a copy outside this repo (password manager or private backup), put it back before building on a new PC, and never commit it.

## GitHub access

The repo is public, so cloning does not need a login. Pushing requires GitHub authentication; Git Credential Manager can open a browser login and reuse the stored credentials for later pushes.

## Connect test

```powershell
D:\Server\Tools\platform-tools\adb.exe connect 192.168.8.202:5555
D:\Server\Tools\platform-tools\adb.exe devices
```

Accept "Allow USB debugging?" on the TV the first time. `devices` must show `192.168.8.202:5555  device` (not `unauthorized`).
