param([string]$Serial = 'emulator-5554', [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk")
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-[0-9]+$') { throw 'Only an explicit emulator serial is allowed.' }
$taskAdb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
$projectRoot = Split-Path -Parent $PSScriptRoot
function Invoke-Adb {
    $output = & $taskAdb -s $Serial @args 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($output -join "`n") }
    return $output
}
if (((Invoke-Adb shell getprop ro.kernel.qemu) -join '').Trim() -ne '1') { throw 'Physical devices are refused.' }
foreach ($apk in @('app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', 'fixture/build/outputs/apk/debug/fixture-debug.apk', 'fixture/build/outputs/apk/landing/fixture-landing.apk')) {
    Invoke-Adb install -r (Join-Path $projectRoot $apk)
}
Invoke-Adb shell input keyevent KEYCODE_WAKEUP
Invoke-Adb shell wm dismiss-keyguard
foreach ($suite in @('all', 'persistence')) {
    $result = Invoke-Adb shell am instrument -w -r -e emulatorSerial $Serial -e suite $suite com.wangqingli.app.test/com.wangqingli.app.DeviceTestRunner
    $result | Tee-Object -FilePath (Join-Path $projectRoot "build/device-$suite.txt")
    if (($result -join "`n") -notmatch 'Device tests: [1-9][0-9]* passed, 0 failed') { throw "Device suite $suite failed; see build/device-$suite.txt" }
}
