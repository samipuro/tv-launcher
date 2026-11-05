# Back up the TV state to backup\<date>[-label]\ before changing anything. Read-only on the TV.
#
# Usage: scripts\backup.ps1 [-TvHost 192.168.8.202] [-Label before-debloat]
param(
    [string]$TvHost = $(if ($env:TV_HOST) { $env:TV_HOST } else { '192.168.8.202' }),
    [string]$Label = ''
)
$ErrorActionPreference = 'Stop'
$adb = if ($env:ADB) { $env:ADB } else { 'D:\Server\Tools\platform-tools\adb.exe' }
$dev = if ($TvHost -like '*:*') { $TvHost } else { "${TvHost}:5555" }
$repo = Split-Path -Parent $PSScriptRoot
$name = (Get-Date -Format 'yyyy-MM-dd_HHmm') + $(if ($Label) { "-$Label" } else { '' })
$out = Join-Path $repo "backup\$name"
New-Item -ItemType Directory -Force $out | Out-Null

& $adb connect $dev | Out-Null

# One shell command per file, saved as UTF-8 without BOM.
$items = [ordered]@{
    'getprop.txt'              = 'getprop'
    'packages-all.txt'         = 'pm list packages -f --show-versioncode'
    'packages-enabled.txt'     = 'pm list packages -e'
    'packages-disabled.txt'    = 'pm list packages -d'
    'packages-system.txt'      = 'pm list packages -s'
    'packages-thirdparty.txt'  = 'pm list packages -3 --show-versioncode'
    'packages-uninstalled.txt' = 'pm list packages -u'
    'settings-global.txt'      = 'settings list global'
    'settings-secure.txt'      = 'settings list secure'
    'settings-system.txt'      = 'settings list system'
    'home-activity.txt'        = 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
    'home-candidates.txt'      = 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
    'focused-app.txt'          = "dumpsys activity activities | grep -E 'ResumedActivity|mFocusedApp'"
    'accessibility.txt'        = 'settings get secure enabled_accessibility_services'
    'wifi-owner.txt'           = "dumpsys wifi | grep -o 'cname=[^ ]*' | sort -u"
    'storage.txt'              = 'df -h /data'
    'memory.txt'               = 'cat /proc/meminfo'
    'users.txt'                = 'pm list users'
    'launcher-dock.txt'        = "dumpsys activity com.cosyhome.tv/.HomeActivity | grep 'cosy dock='"
}
$utf8 = New-Object System.Text.UTF8Encoding $false
foreach ($file in $items.Keys) {
    $text = (& $adb -s $dev shell $items[$file]) -join "`n"
    [IO.File]::WriteAllText((Join-Path $out $file), $text, $utf8)
}

# Redact device identifiers (serial, android_id, Bluetooth MAC) so the backup can live in a public repo.
$ids = @(
    (& $adb -s $dev shell getprop ro.serialno),
    (& $adb -s $dev shell settings get secure android_id),
    (& $adb -s $dev shell settings get secure bluetooth_address)
) | ForEach-Object { "$_".Trim() } | Where-Object { $_ -and $_ -ne 'null' }
foreach ($f in Get-ChildItem $out -Filter *.txt) {
    $text = [IO.File]::ReadAllText($f.FullName)
    foreach ($id in $ids) { $text = $text.Replace($id, '<redacted>') }
    [IO.File]::WriteAllText($f.FullName, $text, $utf8)
}

# Screenshot of the current screen (empty when Live TV or HDMI is showing). adb reports progress on stderr, which Windows PowerShell would treat as an error.
$ErrorActionPreference = 'Continue'
& $adb -s $dev shell screencap -p /sdcard/backup-screen.png
& $adb -s $dev pull /sdcard/backup-screen.png (Join-Path $out 'screen.png') | Out-Null
& $adb -s $dev shell rm /sdcard/backup-screen.png

Write-Output "Backup written to $out"
