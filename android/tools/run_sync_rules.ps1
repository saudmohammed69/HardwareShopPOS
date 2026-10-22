param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$EmulatorCache,
    [Parameter(Mandatory=$true)][string]$SocketTempDirectory
)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$env:NODE_OPTIONS='--use-system-ca'
$env:JAVA_HOME=$JavaHome
$env:PATH="$JavaHome\bin;$env:PATH"
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir="'+$SocketTempDirectory+'"'
$env:FIREBASE_EMULATORS_PATH=$EmulatorCache
$env:XDG_CONFIG_HOME=Join-Path $projectRoot 'build\firebase-test-config'
$env:CI='true'
$env:FIREBASE_CLI_DISABLE_USAGE='true'
Push-Location $projectRoot
try {
    & '.\tools\rules-tests\node_modules\.bin\firebase.cmd' emulators:exec --project demo-hardware-shop-sync --only firestore --config tools/rules-tests/firebase.json --non-interactive 'npm.cmd --prefix tools/rules-tests test' *> 'rules-verification.log'
    $result=$LASTEXITCODE
    Get-Content 'rules-verification.log' -Tail 28
    exit $result
} finally { Pop-Location }
