param([string]$Classes='com.hardwareshop.pos.WebsitePhase2SafetyTest,com.hardwareshop.pos.ReminderUiIntegrationTest,com.hardwareshop.pos.MenuKeyboardRegressionTest',[string]$Log='android-baseline.log')
$ErrorActionPreference='Stop'
$taskAdb='C:\Users\mohammed saud\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$taskProject=(Resolve-Path "$PSScriptRoot\..\..").Path
$taskDevice='emulator-5598'
New-Item -ItemType Directory -Path "$PSScriptRoot\evidence" -Force | Out-Null
if($Log -match '^phase4[hij]-' -and (Test-Path -LiteralPath "$PSScriptRoot\evidence\$Log")){throw 'Preserve existing native attempt; use a new reviewed evidence label'}
# The caller starts the explicitly owned emulator. Never wait indefinitely if
# it dies between boot validation and installation.
$taskDeviceState=((& $taskAdb -s $taskDevice get-state 2>$null)-join '').Trim()
if($taskDeviceState -ne 'device'){throw 'Disposable emulator missing or offline; native setup is blocked'}
$taskQemu=(& $taskAdb -s $taskDevice shell getprop ro.kernel.qemu) -join ''
if($taskQemu.Trim() -ne '1'){throw 'Tests require our disposable emulator, never a shop phone'}
# Wait for Android services used by test APK installation and software-keyboard setup.
$taskReady=$false
$taskDeadline=[DateTime]::UtcNow.AddMinutes(3)
while([DateTime]::UtcNow -lt $taskDeadline){
    $taskBoot=(& $taskAdb -s $taskDevice shell getprop sys.boot_completed) -join ''
    $taskPackage=(& $taskAdb -s $taskDevice shell service check package) -join ''
    $taskSettings=(& $taskAdb -s $taskDevice shell service check settings) -join ''
    $taskIme=(& $taskAdb -s $taskDevice shell settings get secure default_input_method) -join ''
    $taskImeList=(& $taskAdb -s $taskDevice shell ime list -s) -join "`n"
    if($taskBoot.Trim() -eq '1' -and $taskPackage -match 'found' -and $taskSettings -match 'found' -and
       $taskIme.Trim() -and $taskImeList.Contains($taskIme.Trim())){$taskReady=$true;break}
    Start-Sleep -Seconds 2
}
if(-not $taskReady){throw 'Disposable emulator did not finish booting package/settings/IME services within 3 minutes'}
# Apply environment setup after services exist, never against the attached phone.
& $taskAdb -s $taskDevice shell svc wifi disable
& $taskAdb -s $taskDevice shell svc data disable
& $taskAdb -s $taskDevice shell input keyevent 224
& $taskAdb -s $taskDevice shell wm dismiss-keyguard
& $taskAdb -s $taskDevice shell settings put system screen_off_timeout 2147483647
& $taskAdb -s $taskDevice install -r -g "$taskProject\app\build\outputs\apk\debug\app-debug.apk"
if($LASTEXITCODE -ne 0){throw 'App install failed'}
& $taskAdb -s $taskDevice install -r -g "$taskProject\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
if($LASTEXITCODE -ne 0){throw 'Test install failed'}
& $taskAdb -s $taskDevice shell cmd package compile -m speed -f com.hardwareshop.pos.debug
& $taskAdb -s $taskDevice shell cmd package compile -m speed -f com.hardwareshop.pos.debug.test
# AVD has a hardware keyboard; explicitly exercise the software IME as required.
& $taskAdb -s $taskDevice shell settings put secure show_ime_with_hard_keyboard 1
if($LASTEXITCODE -ne 0){throw 'Could not enable the software keyboard on the disposable emulator'}
$taskShowIme=(& $taskAdb -s $taskDevice shell settings get secure show_ime_with_hard_keyboard) -join ''
if($taskShowIme.Trim() -ne '1'){throw 'Software keyboard setting was not applied'}
& $taskAdb -s $taskDevice logcat -c
& $taskAdb -s $taskDevice shell am instrument -w -e class $Classes com.hardwareshop.pos.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | Tee-Object -FilePath "$PSScriptRoot\evidence\$Log"
& $taskAdb -s $taskDevice logcat -d -s MenuPerformance:I NavigationProbe:I BillingLoadTrace:I AndroidRuntime:E | Set-Content -LiteralPath "$PSScriptRoot\evidence\$Log.logcat.txt"
$taskBackupPath='/sdcard/Android/data/com.hardwareshop.pos.debug/files/website-phase2'
& $taskAdb -s $taskDevice shell test -d $taskBackupPath
$taskHasBackupArtifacts=$LASTEXITCODE -eq 0
if($taskHasBackupArtifacts){
    $taskArtifactName=if($Log -match '^phase4[hij]-'){[IO.Path]::GetFileNameWithoutExtension($Log)+'-android-backup'}elseif($Log -like 'phase4f-*'){'phase4f-android-backup'}else{'android-backup'}
    & $taskAdb -s $taskDevice pull $taskBackupPath "$PSScriptRoot\evidence\$taskArtifactName"
    if($LASTEXITCODE -ne 0){throw 'Backup artifacts exist but could not be collected'}
}elseif($Classes -match 'WebsitePhase2SafetyTest'){
    throw 'Backup safety suite did not produce its required recovery artifacts'
}else{Write-Output 'No backup artifacts expected from these test classes'}
# Transfer private snapshots with run-as and binary stdout; adb pull cannot
# read VACUUM INTO files created with private app permissions. Collect all
# available evidence and a stopped process image before reporting failure.
$taskResult=Get-Content -LiteralPath "$PSScriptRoot/evidence/$Log" -Raw
$taskPassed=($taskResult -match 'OK \((\d+) tests?\)' -and $taskResult -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed')
if($Log -match '^phase4[ij]-'){
 $taskCollectArgs=@("$PSScriptRoot/collect-native-evidence.py",'--label',[IO.Path]::GetFileNameWithoutExtension($Log))
 if(-not $taskPassed){$taskCollectArgs+='--failed'}
 & 'C:/Users/mohammed saud/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' @taskCollectArgs
 if($LASTEXITCODE -ne 0){throw 'Native evidence collection failed; original test result and partial evidence retained'}
}
if(-not $taskPassed){throw 'Android regression suite failed; inspect original test result and retained evidence'}
$global:LASTEXITCODE=0
