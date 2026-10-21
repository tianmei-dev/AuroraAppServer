# Offline-friendly bootRun: uses D: Gradle cache (avoids C: full / download timeout).
$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location $projectRoot

$env:GRADLE_USER_HOME = "D:\Android_Work_Server\.gradle-home"
$env:TEMP = "D:\Android_Work_Server\.tmp"
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $env:TEMP | Out-Null

Write-Host "GRADLE_USER_HOME=$env:GRADLE_USER_HOME"
Write-Host "Starting bootRun ..."
& "$projectRoot\gradlew.bat" bootRun @args
