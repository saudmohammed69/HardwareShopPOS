param([string]$Classes='com.hardwareshop.pos.WebsiteNativeIntegrationTest,com.hardwareshop.pos.WebsiteOrdersUiTest',[string]$Log='phase3b-native.log')
$ErrorActionPreference='Stop'
$taskAdb='C:\Users\mohammed saud\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $taskAdb -s emulator-5598 emu avd start
& $taskAdb -s emulator-5598 wait-for-device
if(((& $taskAdb -s emulator-5598 shell getprop ro.kernel.qemu)-join '').Trim() -ne '1'){throw 'Only the disposable emulator may run the native fixture'}
& $taskAdb -s emulator-5598 reverse tcp:4316 tcp:4316
& $taskAdb -s emulator-5598 reverse tcp:4320 tcp:4320
try { & "$PSScriptRoot\run-android.ps1" -Classes $Classes -Log $Log }
finally {
    $taskScreenshots=if($Log -like 'phase4i-*'){[IO.Path]::GetFileNameWithoutExtension($Log)+'-screenshots'}else{'native-screenshots'}
    & $taskAdb -s emulator-5598 pull /sdcard/Android/data/com.hardwareshop.pos.debug/files/website-phase3b "$PSScriptRoot\evidence\$taskScreenshots"
    & $taskAdb -s emulator-5598 logcat -d -s TestRunner:I AndroidRuntime:E | Set-Content -LiteralPath "$PSScriptRoot\evidence\$Log.diagnostics.txt"
}
