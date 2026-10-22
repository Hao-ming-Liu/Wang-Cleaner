[CmdletBinding()]
param(
    [string]$JavaHome,
    [string]$AndroidSdk,
    [switch]$Test
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

function Find-ToolDirectory {
    param([string[]]$Candidates, [string]$RelativeTool, [string]$Label)
    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate $RelativeTool))) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw "$Label was not found. Pass its directory with -$Label."
}

$javaCandidates = @(
    $JavaHome,
    $env:JAVA_HOME,
    (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'),
    (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr'),
    'C:\studyy\Android\Android Studio\jbr',
    'C:\Android\Android Studio\jbr'
)
$sdkCandidates = @(
    $AndroidSdk,
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
)

$env:JAVA_HOME = Find-ToolDirectory $javaCandidates 'bin\javac.exe' 'JavaHome'
$env:ANDROID_HOME = Find-ToolDirectory $sdkCandidates 'platforms\android-35\android.jar' 'AndroidSdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
if (-not $env:GRADLE_USER_HOME) {
    $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
}

$buildTools = Join-Path $env:ANDROID_HOME 'build-tools\34.0.0'
foreach ($tool in @('aapt2.exe', 'd8.bat', 'zipalign.exe', 'apksigner.bat')) {
    if (-not (Test-Path -LiteralPath (Join-Path $buildTools $tool))) {
        throw "Android Build Tools 34.0.0 is incomplete: $tool is missing."
    }
}

# local.properties takes precedence over ANDROID_HOME in Android Gradle Plugin.
# Keep it consistent with the SDK selected above, including paths with Unicode.
$sdkPropertyValue = $env:ANDROID_HOME.Replace('\', '/')
$sdkPropertyValue = [regex]::Replace($sdkPropertyValue, '[^\x20-\x7e]', {
    param($match)
    '\u{0:x4}' -f [int][char]$match.Value
})
[System.IO.File]::WriteAllText(
    (Join-Path $projectRoot 'local.properties'),
    "sdk.dir=$sdkPropertyValue`n",
    [System.Text.Encoding]::ASCII
)

Write-Host "Java: $env:JAVA_HOME"
Write-Host "Android SDK: $env:ANDROID_HOME"
Write-Host "Gradle cache: $env:GRADLE_USER_HOME"
Push-Location -LiteralPath $projectRoot
try {
    if ($Test) {
        & (Join-Path $PSScriptRoot 'test-core.ps1')
    }
    $gradleTasks = @(':app:assembleDebug', ':fixture:assembleDebug')
    & (Join-Path $projectRoot 'gradlew.bat') --offline --no-daemon --console=plain @gradleTasks
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed with exit code $LASTEXITCODE."
    }
    Write-Host "App APK: $(Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk')"
    Write-Host "Fixture APK: $(Join-Path $projectRoot 'fixture\build\outputs\apk\debug\fixture-debug.apk')"
} finally {
    Pop-Location
}
