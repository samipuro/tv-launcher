# Disable the packages in a debloat list on the TV, one pm command per adb call. Never uninstalls.
# Each disabled package is appended to debloat\applied.log. Undo with scripts\restore.ps1.
#
# Usage: scripts\disable.ps1 debloat\debloat.txt [-Batch 3] [-TvHost 192.168.8.202] [-WhatIf]
#
# Safety checks, ported from cleanyourtv's tools\disable.ps1 (v3.4.0). Each skipped package is named
# with the reason; the override switch disables that one check for this run only:
#   core Android / TV middleware packages   refused, no override
#   setupwraith owns the saved Wi-Fi         skipped   -ForceSetupwraith
#   the app that owns Home                   skipped   -ForceHome
#   HDMI/AV/tuner input drivers              skipped   -AllowTvInput
#   apps that open the inputs or Settings    skipped   -AllowHandlers
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$List,
    [int]$Batch = 0,
    [string]$TvHost = $(if ($env:TV_HOST) { $env:TV_HOST } else { '192.168.8.202' }),
    [switch]$WhatIf,
    [switch]$ForceSetupwraith,
    [switch]$ForceHome,
    [switch]$AllowTvInput,
    [switch]$AllowHandlers
)
$ErrorActionPreference = 'Stop'
$adb = if ($env:ADB) { $env:ADB } else { 'D:\Server\Tools\platform-tools\adb.exe' }
$dev = if ($TvHost -like '*:*') { $TvHost } else { "${TvHost}:5555" }
$repo = Split-Path -Parent $PSScriptRoot
$log = Join-Path $repo 'debloat\applied.log'
$pkgShape = '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$'
$setup = 'com.google.android.tungsten.setupwraith'

# Read package names, keeping track of the "# Batch N:" headers.
$pkgs = @()
$current = 0
foreach ($line in Get-Content $List) {
    if ($line -match '^#\s*Batch\s+(\d+)') { $current = [int]$Matches[1]; continue }
    $name = ($line -replace '#.*$', '').Trim()
    if (-not $name) { continue }
    if ($name -notmatch $pkgShape) { throw "not a package name: $name" }
    if ($Batch -eq 0 -or $Batch -eq $current) { $pkgs += $name }
}

# Core Android packages and the TV middleware: with one of these disabled the TV may never boot again
# (cleanyourtv #884; #1711 bare android.ext.*; #1578 a Haier boot loop on the middleware; #2557 SDK
# sandbox; #2803 a TCL boot loop with only location.fused off). The whole list is checked, not only the
# chosen batch, and there is no override. Same patterns as tools\disable.ps1, disable.sh and recovery.py.
$coreRe = '^(android|android\.ext\.(services|shared)|com\.android\.sdksandbox|com\.android\.location\.fused|com\.android\.systemui|com\.android\.shell|com\.android\.phone|com\.android\.settings|com\.android\.tv\.settings|com\.(google\.)?android\.(permissioncontroller|packageinstaller|ext\.services|ext\.shared|modulemetadata)|com\.google\.android\.overlay\.modules.*|com\.android\.providers\..*|com\.cltv\.mal|com\.mediatek\.(tis|tv\.service|tv\.agent))$'
$coreOk = '^com\.android\.providers\.(calendar|contacts|userdictionary|blockednumber)$'
$all = @(Get-Content $List | ForEach-Object { ($_ -replace '#.*$', '').Trim() } | Where-Object { $_ })
$core = @($all | Where-Object { $_ -cmatch $coreRe -and $_ -cnotmatch $coreOk })
if ($core.Count -gt 0) {
    throw "refusing ${List}: it disables core Android or TV middleware packages, and without them the TV may not boot again: $($core -join ', '). Take these lines out of the list and run it again. Nothing was changed on the TV."
}

# One shell command on the TV; $null when adb itself failed. Windows PowerShell turns redirected
# stderr into an error that 'Stop' would throw, so it is relaxed for the call.
function Shell([string]$cmd) {
    $ErrorActionPreference = 'Continue'
    $out = @(& $adb -s $dev shell $cmd 2>$null)
    if ($LASTEXITCODE -ne 0) { return $null }
    return @($out | ForEach-Object { "$_".Trim() })
}
function Skip([string]$p, [string]$why) {
    Write-Warning "Skipping ${p}: $why"
    $script:pkgs = @($script:pkgs | Where-Object { $_ -ne $p })
}

& $adb connect $dev | Out-Null
$answer = Shell 'pm list packages -e --user 0'
if (-not $answer) { throw "the TV at $dev did not answer 'pm list packages'. Nothing was changed." }
$enabled = @($answer) -replace '^package:', ''
$pkgs = @($pkgs | Where-Object {
    if ($enabled -contains $_) { $true } else { Write-Host "skip  $_ (not installed or already disabled)"; $false }
})

# A Wi-Fi network saved during the first-run wizard belongs to setupwraith, and Android deletes it when
# setupwraith is disabled. If the owners cannot be read, setupwraith stays on.
if ($pkgs -contains $setup -and -not $ForceSetupwraith) {
    $wifi = (Shell 'dumpsys wifi') -join "`n"
    if ($wifi -match 'cname=[^ ]*setupwraith') {
        Skip $setup 'the saved Wi-Fi network belongs to it, and disabling it would delete that network. Forget the network and join it again in Settings > Network & Internet, then run this again (or pass -ForceSetupwraith on Ethernet).'
    } elseif ($wifi -notmatch 'cname=') {
        Skip $setup 'could not check which app owns the saved Wi-Fi networks. On Ethernet with no saved Wi-Fi, pass -ForceSetupwraith.'
    }
}

# Home: never disable the app that owns Home while nothing staying enabled can take over. setupwraith
# answers Home ahead of other launchers, so while it stays on, Home would land on its black
# RecoveryActivity (cleanyourtv #239, #1573).
if (-not $ForceHome -and $pkgs.Count -gt 0) {
    $homeLine = @(Shell 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME') | Where-Object { $_ } | Select-Object -Last 1
    $homePkg = "$homeLine" -split '/' | Select-Object -First 1
    $claims = @(Shell 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME') |
        Where-Object { $_ -match '^([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)$' } |
        ForEach-Object { [pscustomobject]@{ Pkg = $Matches[1]; Cls = $Matches[2] } }
    $notLaunchers = @($setup, 'com.android.tv.settings', 'com.android.settings', 'com.android.provision')
    $launchers = @($claims | Where-Object { $_.Cls -notmatch 'FallbackHome$' -and $notLaunchers -notcontains $_.Pkg } | ForEach-Object { $_.Pkg } | Sort-Object -Unique)
    $setupStays = @($claims | Where-Object { $_.Pkg -eq $setup }).Count -gt 0 -and $pkgs -notcontains $setup
    if ($homePkg -notmatch $pkgShape) {
        # No single owner (the chooser "android", or no answer): keep every launcher in the list.
        foreach ($p in @($launchers + 'com.google.android.apps.tv.launcherx' | Where-Object { $pkgs -contains $_ })) {
            Skip $p 'could not tell which app owns Home, and it is a launcher. Make Cosy Home Home first (scripts\launcher-install.ps1 -TakeHome), or pass -ForceHome.'
        }
    } elseif ($pkgs -contains $homePkg) {
        $others = @($launchers | Where-Object { $_ -ne $homePkg -and $pkgs -notcontains $_ })
        if ($others.Count -eq 0) {
            Skip $homePkg 'it owns Home and no other launcher stays enabled. Make Cosy Home Home first (scripts\launcher-install.ps1 -TakeHome), or pass -ForceHome.'
        } elseif ($setupStays) {
            Skip $homePkg "it owns Home, and $setup stays enabled and would take Home to its black recovery screen. Make Cosy Home Home first, or disable setupwraith in the same run, or pass -ForceHome."
        }
    }
}

# Input drivers: a package that asks for TV_INPUT_HARDWARE (or serves TvInputService) is the software
# under HDMI/AV/tuner. Disable it and the inputs show No Signal until a full restore and a cold boot
# (cleanyourtv #321). A dump that cannot be read keeps the package on (#1753); from Android 6 a readable
# dump with no permission block asks for nothing and passes (#2398).
if (-not $AllowTvInput -and $pkgs.Count -gt 0) {
    $sdk = [int]("$(Shell 'getprop ro.build.version.sdk')" -replace '\D', '0')
    foreach ($p in @($pkgs)) {
        $d = (Shell "dumpsys package $p") -join "`n"
        if ($d -match 'hung system process') {
            Skip $p 'the TV answers dumpsys with "Service dumps disabled due to hung system process", so the input-driver check cannot read it. Reboot the TV and run the list again.'
        } elseif ($d -match '(?s)requested permissions:(.*?)((install|runtime) permissions:|User \d+:|$)' -or $d -match '(?s)grantedPermissions:(.*?)((install|runtime) permissions:|User \d+:|$)') {
            if ($Matches[1] -match 'TV_INPUT_HARDWARE') { Skip $p 'it asks for TV_INPUT_HARDWARE, so it drives the HDMI/AV/tuner inputs; disabled, they stay black. Pass -AllowTvInput to disable it anyway.' }
        } elseif ($d -match 'android\.media\.tv\.TvInputService') {
            Skip $p 'it serves TvInputService, so it drives the HDMI/AV/tuner inputs. Pass -AllowTvInput to disable it anyway.'
        } elseif ($d -notmatch [regex]::Escape("Package [$p]") -or $sdk -lt 23) {
            Skip $p 'its dumpsys could not be read for the input-driver check. Once every input still works, pass -AllowTvInput to disable it.'
        }
    }
}

# Keep the apps the TV opens its inputs (VIEW on a channel) and Settings with, asked from the TV.
if (-not $AllowHandlers -and $pkgs.Count -gt 0) {
    $q = 'cmd package query-activities --brief'
    $inputs = @(Shell "$q -a android.intent.action.VIEW -t vnd.android.cursor.item/channel") | Where-Object { $_ -match '^([A-Za-z0-9_.]+)/' } | ForEach-Object { $Matches[1] }
    $settings = @(Shell "$q -a android.settings.SETTINGS") | Where-Object { $_ -match '^([A-Za-z0-9_.]+)/' } | ForEach-Object { $Matches[1] }
    if ($settings.Count -eq 0) {
        Write-Warning 'Could not check which listed packages open the inputs and Settings. After this run, open an HDMI input and Settings from the launcher before you put the remote down.'
    }
    foreach ($p in @($pkgs | Where-Object { $inputs -contains $_ })) { Skip $p 'the TV opens its HDMI/AV inputs with it. Pass -AllowHandlers to disable it anyway.' }
    foreach ($p in @($pkgs | Where-Object { $settings -contains $_ })) { Skip $p 'the TV opens Settings with it (and with it Developer options). Pass -AllowHandlers to disable it anyway.' }
}

foreach ($p in $pkgs) {
    if ($WhatIf) { Write-Output "would disable  $p"; continue }
    $res = (& $adb -s $dev shell pm disable-user --user 0 $p) -join ' '
    Write-Output "$p -> $res"
    Add-Content -Path $log -Value ("{0}`t{1}`t{2}" -f (Get-Date -Format 's'), $p, $res.Trim())
}

# Read the TV back instead of trusting pm's answers (cleanyourtv #1376).
$stillOn = @()
if (-not $WhatIf -and $pkgs.Count -gt 0) {
    $answer = Shell 'pm list packages -e --user 0'
    $after = @($answer) -replace '^package:', ''
    if (-not $answer) {
        Write-Warning "Could not read back what is still enabled. Compare 'pm list packages -d --user 0' with the list by hand."
    } else {
        $stillOn = @($pkgs | Where-Object { $after -contains $_ })
        if ($stillOn.Count -gt 0) { Write-Warning "Still enabled after this run: $($stillOn -join ', '). The TV refused pm disable-user for these (answer above). Do not rerun in a loop; try once more after a reboot." }
    }
}

# With ro.adb.secure 0 or unset, port 5555 accepts any device without the "Allow" prompt (#1720).
# A failed read only skips the note.
$secure = Shell 'getprop ro.adb.secure'
if ($null -ne $secure -and "$secure" -in @('0', '')) {
    Write-Warning 'This TV does not require the "Allow" prompt for network ADB (ro.adb.secure is 0 or unset). Close ADB when you are done (docs\04-close-adb.md).'
}
if ($stillOn.Count -gt 0) { exit 1 }
