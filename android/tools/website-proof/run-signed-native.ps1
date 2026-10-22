param([string]$Log='phase4d-signed-native.log')
$ErrorActionPreference='Stop'
$taskAdb='C:\Users\mohammed saud\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$taskDevice='emulator-5598'
& "$PSScriptRoot\run-android.ps1" -Classes 'com.hardwareshop.pos.WebsiteDeviceSecurityTest#keystoreKeyIsNonExportableAndProofSignatureInteroperates,com.hardwareshop.pos.WebsiteDeviceSecurityTest#interruptedEnrollmentMetadataSurvivesRestartButRestoredMetadataCannotAuthorizeANewInstallation,com.hardwareshop.pos.WebsiteDeviceSecurityTest#stagedRotationIsDurableAndReinstallCannotReuseItsMetadata' -Log "$Log.keys.log"
if(((& $taskAdb -s $taskDevice shell getprop ro.kernel.qemu)-join '').Trim() -ne '1'){throw 'Disposable emulator required'}
& $taskAdb -s $taskDevice shell settings put global auto_time 0
& $taskAdb -s $taskDevice shell cmd alarm set-time ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
$taskGuestSeconds=[long](((& $taskAdb -s $taskDevice shell date +%s)-join '').Trim())
$taskHostSeconds=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
[pscustomobject]@{guestSeconds=$taskGuestSeconds;hostSeconds=$taskHostSeconds;skewSeconds=($taskGuestSeconds-$taskHostSeconds);signatureWindowSeconds=60;windowUnchanged=$true} | ConvertTo-Json | Set-Content "$PSScriptRoot\evidence\$Log.clock.json"
if([Math]::Abs($taskGuestSeconds-$taskHostSeconds) -gt 5){throw 'Emulator clock is unstable; do not widen the signature acceptance window'}
& $taskAdb -s $taskDevice reverse tcp:4318 tcp:4318
if($LASTEXITCODE -ne 0){throw 'Synthetic secure bridge reverse failed'}
$taskControl=Join-Path $PSScriptRoot '.local-state\phase4b-native\control.json'
& $taskAdb -s $taskDevice push $taskControl /data/local/tmp/phase4b-control.json
if($LASTEXITCODE -ne 0){throw 'Synthetic control fixture transfer failed'}
try {
    & $taskAdb -s $taskDevice shell run-as com.hardwareshop.pos.debug cp /data/local/tmp/phase4b-control.json no_backup/website-phase4b-control.json
    if($LASTEXITCODE -ne 0){throw 'Synthetic fixture provisioning failed'}
    & $taskAdb -s $taskDevice shell am instrument -w -e class 'com.hardwareshop.pos.WebsiteDeviceSecurityTest#androidSignedEnrollmentApprovalBillingRetriesRevocationAndOrdinaryOfflineBilling,com.hardwareshop.pos.WebsiteDeviceSecurityTest#signedWorkerRotationRecoversLostResponseAndRestartThenRevocationPreservesUncertainty,com.hardwareshop.pos.WebsiteDeviceSecurityTest#individualWorkerBindingGuardsHandoffAndRejectsSwitchRestoreAndRevocation' com.hardwareshop.pos.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | Tee-Object -FilePath "$PSScriptRoot\evidence\$Log"
    $taskResult=Get-Content -LiteralPath "$PSScriptRoot\evidence\$Log" -Raw
    if($taskResult -notmatch 'OK \(3 tests\)' -or $taskResult -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed'){throw 'Signed native tests failed; evidence retained'}
    foreach($taskMethod in @('beginLostRotationForSeparateProcessRecovery','finishLostRotationFromPreviousInstrumentationProcess')){
        # Separate instrument invocations plus force-stop prove metadata/Keystore
        # recovery across actual app process death, not only object reconstruction.
        & $taskAdb -s $taskDevice shell am force-stop com.hardwareshop.pos.debug
        $taskProcessLog="$PSScriptRoot\evidence\$Log.$taskMethod.log"
        & $taskAdb -s $taskDevice shell am instrument -w -e class "com.hardwareshop.pos.WebsiteDeviceSecurityTest#$taskMethod" com.hardwareshop.pos.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | Tee-Object -FilePath $taskProcessLog
        $taskProcessResult=Get-Content -LiteralPath $taskProcessLog -Raw
        if($taskProcessResult -notmatch 'OK \(1 test\)' -or $taskProcessResult -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed'){throw 'Separate-process key recovery failed; retained for review'}
    }
} finally {
    & $taskAdb -s $taskDevice shell run-as com.hardwareshop.pos.debug rm -f no_backup/website-phase4b-control.json
    & $taskAdb -s $taskDevice shell rm -f /data/local/tmp/phase4b-control.json
    & $taskAdb -s $taskDevice logcat -d -s TestRunner:I AndroidRuntime:E | Set-Content -LiteralPath "$PSScriptRoot\evidence\$Log.diagnostics.txt"
}
