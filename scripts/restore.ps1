# Put the TV back the way it was before this setup:
#  - re-enable every package in the debloat list that was enabled in the baseline backup
#    (packages Sony disabled at the factory stay disabled),
#  - re-enable the stock Google TV launcher and make it Home again,
#  - switch Private DNS back to automatic,
#  - with -RemoveLauncher, uninstall the custom launcher.
#
# Usage: scripts\restore.ps1 [-List debloat\debloat.txt] [-Baseline backup\2026-10-21_1237-before-debloat]
#                            [-RemoveLauncher] [-TvHost 192.168.8.202] [-WhatIf]
param(
    [string]$List = '',
    [string]$Baseline = '',
    [switch]$RemoveLauncher,
    [string]$TvHost = $(if ($env:TV_HOST) { $env:TV_HOST } else { '192.168.8.202' }),
    [switch]$WhatIf
)
$ErrorActionPreference = 'Stop'
$adb = if ($env:ADB) { $env:ADB } else { 'D:\Server\Tools\platform-tools\adb.exe' }
$dev = if ($TvHost -like '*:*') { $TvHost } else { "${TvHost}:5555" }
$repo = Split-Path -Parent $PSScriptRoot
if (-not $List) { $List = Join-Path $repo 'debloat\debloat.txt' }
if (-not $Baseline) { $Baseline = Join-Path $repo 'backup\2026-10-21_1237-before-debloat' }
$stock = 'com.google.android.apps.tv.launcherx'
$custom = 'com.cosyhome.tv'

function Run([string[]]$cmd) {
    if ($WhatIf) { Write-Output ("would run: " + ($cmd -join ' ')); return }
    $res = (& $adb -s $dev shell @cmd) -join ' '
    Write-Output (($cmd -join ' ') + " -> " + $res)
}

$baseDisabled = (Get-Content (Join-Path $Baseline 'packages-disabled.txt')) -replace '^package:', '' | ForEach-Object { $_.Trim() }
$pkgs = Get-Content $List | ForEach-Object { ($_ -replace '#.*$', '').Trim() } | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$' }

& $adb connect $dev | Out-Null

# Setup wizard and stock launcher first, so the TV always has a Home. Only what the baseline had on:
# turning on a launcher the TV had off before this setup is not a restore (cleanyourtv #1714).
$setup = 'com.google.android.tungsten.setupwraith'
$stockWasOn = $baseDisabled -notcontains $stock
if ($stockWasOn) {
    Run @('pm', 'enable', $stock)
} elseif ($RemoveLauncher) {
    throw "refusing -RemoveLauncher: the baseline has $stock disabled, so removing Cosy Home would leave the TV with no Home. Run without -RemoveLauncher. Nothing was changed."
} else {
    Write-Output "keep disabled  $stock (disabled before this setup; Cosy Home stays Home)"
}
if ($baseDisabled -notcontains $setup) { Run @('pm', 'enable', $setup) }
if ($stockWasOn) { Run @('cmd', 'package', 'set-home-activity', "$stock/.home.HomeActivity") }

# Re-enable the debloat list, one pm call each.
foreach ($p in $pkgs) {
    if ($baseDisabled -contains $p) { Write-Output "keep disabled  $p (disabled before this setup)"; continue }
    Run @('pm', 'enable', $p)
}

# Screensaver delay back to the original 10 minutes (this setup uses 5).
Run @('settings', 'put', 'system', 'screen_off_timeout', '600000')

# Private DNS back to automatic.
Run @('settings', 'put', 'global', 'private_dns_mode', 'opportunistic')
Run @('settings', 'delete', 'global', 'private_dns_specifier')

# Remove the launcher and its remote-button accessibility service.
if ($RemoveLauncher) {
    Run @('settings', 'delete', 'secure', 'enabled_accessibility_services')
    Run @('settings', 'put', 'secure', 'accessibility_enabled', '0')
    Run @('cmd', 'notification', 'disallow_listener', "$custom/$custom.MediaListener")
    Run @('pm', 'uninstall', $custom)
}

Write-Output 'Done. Press Home on the remote; reboot the TV with: adb reboot'
