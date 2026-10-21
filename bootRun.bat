@echo off
REM Offline-friendly local start for VS Code / PowerShell / CMD.
REM Uses D: Gradle cache so gradlew does NOT download from services.gradle.org.

set "GRADLE_USER_HOME=D:\Android_Work_Server\.gradle-home"
set "TEMP=D:\Android_Work_Server\.tmp"
set "TMP=D:\Android_Work_Server\.tmp"
if not exist "%TEMP%" mkdir "%TEMP%"
if not exist "%GRADLE_USER_HOME%" mkdir "%GRADLE_USER_HOME%"

cd /d "%~dp0"
echo GRADLE_USER_HOME=%GRADLE_USER_HOME%
call "%~dp0gradlew.bat" bootRun %*
