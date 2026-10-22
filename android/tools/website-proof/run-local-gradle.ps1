param([string[]]$Tasks=@(':app:testDebugUnitTest'),[string]$Log='phase4d-gradle-tests.log')
$ErrorActionPreference='Stop'
$taskProject=(Resolve-Path "$PSScriptRoot\..\..").Path
$taskSocketDir=Join-Path $taskProject 'build\local-java-sockets'
New-Item -ItemType Directory -Path $taskSocketDir -Force | Out-Null
# JBR Windows AF_UNIX fails in this host's TEMP directory (both path spellings).
# Scope the supported JDK property to this process tree, including test workers.
# No Windows/global environment, Gradle source or application setting is changed.
$taskPrevious=$env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS=($taskPrevious+' "-Djdk.net.unixdomain.tmpdir='+$taskSocketDir+'"').Trim()
    & 'C:\Users\mohammed saud\Documents\Codex\2026-10-15\fix-pipe-pricing-and-billing-flow\work\performance\build.ps1' -Tasks $Tasks -Log $Log
    $taskExit=$LASTEXITCODE
} finally { $env:JAVA_TOOL_OPTIONS=$taskPrevious }
exit $taskExit
