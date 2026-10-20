param(
    [string]$ToolRoot = (Join-Path $env:LOCALAPPDATA 'ImmichTVPlus')
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jdkPath = (Get-Content -LiteralPath (Join-Path $ToolRoot 'jdk-path.txt') -Raw).Trim()
$signing = Get-Content -LiteralPath (Join-Path $ToolRoot 'signing\release-signing.json') -Raw | ConvertFrom-Json
$environment = @{
    JAVA_HOME = $jdkPath
    ANDROID_HOME = (Join-Path $ToolRoot 'android-sdk')
    RELEASE_KEYSTORE_PATH = $signing.keyStorePath
    RELEASE_KEYSTORE_PASSWORD = $signing.storePassword
    RELEASE_KEY_ALIAS = $signing.keyAlias
    RELEASE_KEY_PASSWORD = $signing.keyPassword
}
$previous = @{}

function Invoke-CheckedNative {
    param([string]$FilePath, [string[]]$Arguments)
    # Windows PowerShell treats native stderr (including harmless JVM warnings) as ErrorRecord.
    # Judge success by the process exit code while keeping all diagnostic text in the log.
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $FilePath @Arguments 2>&1 | ForEach-Object { $_.ToString() }
        $nativeExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($nativeExitCode -ne 0) { throw "Native command failed (exit $nativeExitCode): $FilePath" }
}

try {
    foreach ($name in $environment.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $environment[$name], 'Process')
    }
    if (-not (Test-Path -LiteralPath $signing.keyStorePath)) { throw 'Release keystore is missing.' }
    Push-Location -LiteralPath $projectRoot
    try {
        Invoke-CheckedNative (Join-Path $jdkPath 'bin\java.exe') @(
            '-Dorg.gradle.appname=gradlew', '-classpath', 'gradle/wrapper/gradle-wrapper.jar',
            'org.gradle.wrapper.GradleWrapperMain', ':app:testDebugUnitTest',
            ':mediaslider:mediaslider:testDebugUnitTest', ':app:assembleDebug', ':app:assembleRelease',
            '--console=plain', '--max-workers=2'
        )
        $artifactDir = Join-Path $projectRoot 'artifacts'
        New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
        $releaseDir = Join-Path $projectRoot 'app\build\outputs\apk\release'
        $metadata = Get-Content -LiteralPath (Join-Path $releaseDir 'output-metadata.json') -Raw | ConvertFrom-Json
        if (@($metadata.elements).Count -ne 1) { throw 'Expected exactly one universal Release APK output.' }
        $outputName = $metadata.elements[0].outputFile
        if ([IO.Path]::GetFileName($outputName) -ne $outputName -or -not $outputName.EndsWith('.apk')) {
            throw 'Unexpected Release output filename.'
        }
        $artifact = Join-Path $artifactDir $outputName
        Copy-Item -LiteralPath (Join-Path $releaseDir $outputName) -Destination $artifact -Force
        $buildTools = Join-Path $ToolRoot 'android-sdk\build-tools\36.0.0'
        Invoke-CheckedNative (Join-Path $buildTools 'apksigner.bat') @('verify', '--verbose', '--print-certs', $artifact)
        $sha256 = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
        $hashLines = Get-ChildItem -LiteralPath $artifactDir -Filter 'ImmichTVPlus-*-release.apk' | Sort-Object Name | ForEach-Object {
            "$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())  $($_.Name)"
        }
        Set-Content -LiteralPath (Join-Path $artifactDir 'SHA256SUMS.txt') -Value $hashLines -Encoding ASCII
        Write-Output "Release APK: $artifact"
    } finally { Pop-Location }
} finally {
    foreach ($name in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
}
