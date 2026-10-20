param(
    [string]$ToolRoot = (Join-Path $env:LOCALAPPDATA 'ImmichTVPlus'),
    [switch]$ResetAppData
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sdkRoot = Join-Path $ToolRoot 'android-sdk'
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
$emulator = Join-Path $sdkRoot 'emulator\emulator.exe'
$apkDirectory = Join-Path $projectRoot 'artifacts'
$latestApk = Get-ChildItem -LiteralPath $apkDirectory -Filter 'ImmichTVPlus-*-release.apk' |
    Where-Object { $_.Name -match '^ImmichTVPlus-(\d+\.\d+\.\d+)-release\.apk$' } |
    Sort-Object -Property { [version]([regex]::Match($_.Name, '\d+\.\d+\.\d+').Value) } -Descending |
    Select-Object -First 1
if (-not $latestApk) { throw 'No Release APK was found in artifacts. Build a release first.' }
$apk = $latestApk.FullName
$avdName = 'ImmichTVPlus-API36'
$serial = 'emulator-5554'
$packageName = 'com.jerry.immichtv.plus'

foreach ($file in @($adb, $emulator, $apk)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "Required file is missing: $file" }
}

function Invoke-Adb {
    param([string[]]$AdbArguments)
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& $adb -s $serial @AdbArguments 2>&1 | ForEach-Object { $_.ToString() })
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($exitCode -ne 0) { throw "ADB failed: $($output -join ' ')" }
    return $output
}

$previousSdk = $env:ANDROID_HOME
try {
    $env:ANDROID_HOME = $sdkRoot
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $devices = @(& $adb devices 2>&1 | ForEach-Object { $_.ToString() })
        $devicesExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($devicesExitCode -ne 0) { throw 'Cannot start Android Debug Bridge.' }

    if ($devices -match "^$serial\s") {
        $name = Invoke-Adb -AdbArguments @('emu', 'avd', 'name')
        if ($name -notcontains $avdName) {
            throw 'Emulator port 5554 is occupied by a different virtual device. Close that emulator and try again.'
        }
        Write-Output 'Using the existing Immich TV Plus emulator.'
    } else {
        $avdRoot = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { Join-Path $env:USERPROFILE '.android\avd' }
        $avdConfig = Join-Path $avdRoot "$avdName.avd\config.ini"
        if (Test-Path -LiteralPath $avdConfig) {
            $configText = [IO.File]::ReadAllText($avdConfig)
            $configText = [regex]::Replace($configText, '(?m)^hw\.keyboard[ \t]*=[^\r\n]*', 'hw.keyboard = yes')
            $configText = [regex]::Replace($configText, '(?m)^hw\.keyboard\.lid[ \t]*=[^\r\n]*', 'hw.keyboard.lid = no')
            [IO.File]::WriteAllText($avdConfig, $configText, (New-Object Text.UTF8Encoding($false)))
        }
        $logDir = Join-Path $ToolRoot 'logs'
        New-Item -ItemType Directory -Force -Path $logDir | Out-Null
        Write-Output 'Opening the Android TV emulator window. First boot may take a minute.'
        # This is intentionally a visible interactive window for manual TV testing.
        Start-Process -FilePath $emulator -ArgumentList @(
            '-avd', $avdName, '-port', '5554', '-no-snapshot', '-no-boot-anim',
            '-gpu', 'swiftshader', '-memory', '2048', '-cores', '2', '-scale', '0.8'
        ) -WindowStyle Normal -RedirectStandardOutput (Join-Path $logDir 'tv-emulator.out.log') `
          -RedirectStandardError (Join-Path $logDir 'tv-emulator.err.log') | Out-Null
    }

    $deadline = (Get-Date).AddMinutes(3)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        try {
            $boot = Invoke-Adb -AdbArguments @('shell', 'getprop', 'sys.boot_completed')
            if (($boot -join '').Trim() -eq '1') { $ready = $true; break }
        } catch { }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw "The emulator did not finish booting. See $ToolRoot\logs." }

    $apkIsCurrent = $false
    try {
        $installedPaths = @(Invoke-Adb -AdbArguments @('shell', 'pm', 'path', $packageName))
        $baseApk = @($installedPaths | Where-Object { $_ -match '^package:/data/app/[-A-Za-z0-9_~/=.+]+/base\.apk$' })
        if ($baseApk.Count -eq 1) {
            $installedHash = Invoke-Adb -AdbArguments @('shell', 'toybox', 'sha256sum', $baseApk[0].Substring(8))
            $localHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
            $apkIsCurrent = (($installedHash -join ' ').Split(' ')[0] -eq $localHash)
        }
    } catch { $apkIsCurrent = $false }
    if ($apkIsCurrent) {
        Write-Output 'The signed Release APK is already current; skipping reinstallation.'
    } else {
        Write-Output 'Installing the signed Release APK (existing login and settings are preserved).'
        Invoke-Adb -AdbArguments @('install', '-r', $apk)
    }
    if ($ResetAppData) {
        Write-Output 'Resetting data only in this test emulator, as requested by -ResetAppData.'
        Invoke-Adb -AdbArguments @('shell', 'pm', 'clear', $packageName)
    }
    Invoke-Adb -AdbArguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
    Invoke-Adb -AdbArguments @('shell', 'am', 'start', '-W', '-n',
        "$packageName/nl.giejay.android.tv.immich.MainActivity")
    $pythonCommand = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($pythonCommand) {
        $pythonWindowless = Join-Path (Split-Path -Parent $pythonCommand.Source) 'pythonw.exe'
        if (Test-Path -LiteralPath $pythonWindowless) {
            $remoteScript = Join-Path $PSScriptRoot 'tv-remote.py'
            # An interactive remote window is intentional; pythonw avoids a spare console.
            $existingRemote = @(Get-CimInstance Win32_Process -Filter "Name = 'pythonw.exe'" |
                Where-Object { $_.CommandLine -and $_.CommandLine.Contains($remoteScript) })
            if ($existingRemote.Count -eq 0) {
                Start-Process -FilePath $pythonWindowless -ArgumentList @(
                    ('"' + $remoteScript + '"'), '--adb', ('"' + $adb + '"'), '--serial', $serial,
                    '--avd', $avdName
                ) -WindowStyle Normal | Out-Null
            }
        }
    }
    Write-Output 'Ready. Use the mouse-operated PC Remote window (Up/Down/Left/Right/OK/Back).'
    Write-Output 'Enter your Immich server URL and API key inside the app. Close the TV window when finished.'
} finally {
    $env:ANDROID_HOME = $previousSdk
}
