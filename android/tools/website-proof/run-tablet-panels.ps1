$ErrorActionPreference='Stop'
$taskAdb='C:\Users\mohammed saud\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$taskDevice='emulator-5598'
if(((& $taskAdb -s $taskDevice shell getprop ro.kernel.qemu)-join '').Trim() -ne '1'){throw 'Disposable emulator required'}
$taskOriginalSize=((& $taskAdb -s $taskDevice shell wm size)-join "`n")
$taskOriginalDensity=((& $taskAdb -s $taskDevice shell wm density)-join "`n")
$taskSize=if($taskOriginalSize -match 'Override size: (\d+x\d+)'){$Matches[1]}else{$null}
$taskDensity=if($taskOriginalDensity -match 'Override density: (\d+)'){$Matches[1]}else{$null}
try {
    foreach($taskLayout in @(@{Name='tablet-portrait';Size='1200x1920'},@{Name='tablet-landscape';Size='1920x1200'})) {
        & $taskAdb -s $taskDevice shell wm size $taskLayout.Size
        & $taskAdb -s $taskDevice shell wm density 240
        $taskLog="$PSScriptRoot\evidence\android-$($taskLayout.Name).log"
        & $taskAdb -s $taskDevice shell am instrument -w -e displayName "phase2-$($taskLayout.Name)" -e class com.hardwareshop.pos.ResponsiveBillingPanelTest com.hardwareshop.pos.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | Tee-Object -FilePath $taskLog
        $taskText=Get-Content -LiteralPath $taskLog -Raw
        if($taskText -notmatch 'OK \(3 tests\)' -or $taskText -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed'){throw "Panel check failed: $($taskLayout.Name)"}
    }
} finally {
    if($taskSize){& $taskAdb -s $taskDevice shell wm size $taskSize}else{& $taskAdb -s $taskDevice shell wm size reset}
    if($taskDensity){& $taskAdb -s $taskDevice shell wm density $taskDensity}else{& $taskAdb -s $taskDevice shell wm density reset}
}
