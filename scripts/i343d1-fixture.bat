@echo off
REM Isolation fixture CLI (s32it + aurora_app_test only).
REM Usage:
REM   scripts\i343d1-fixture.bat sessions               re-issue sessions for existing accounts (no deletes)
REM   scripts\i343d1-fixture.bat new-pay-accounts [N]   create N fresh payment test accounts (default 3, no deletes)
REM   scripts\i343d1-fixture.bat seed                   DESTRUCTIVE for i343d1-* rows: wipe and recreate D1 dataset
REM   scripts\i343d1-fixture.bat clean                  DESTRUCTIVE for i343d1-* rows: remove D1 dataset
REM Prefer new-pay-accounts over seed/clean when payment tests need clean accounts.
REM Tokens go to .local\i343d1\ (gitignored, untouched by gradlew clean). Never commit or paste them.

set "GRADLE_USER_HOME=D:\Android_Work_Server\.gradle-home"
set "TEMP=D:\Android_Work_Server\.tmp"
set "TMP=D:\Android_Work_Server\.tmp"
if not exist "%TEMP%" mkdir "%TEMP%"

cd /d "%~dp0.."

if "%~1"=="" (
  echo Usage: %~nx0 sessions^|new-pay-accounts [N]^|seed^|clean
  exit /b 2
)

set "CMD=%~1"
set "COUNT=%~2"
if "%COUNT%"=="" set "COUNT=3"
echo Running fixture command=%CMD% (profile=s32it, server.port=0)
REM Fixture classes live in src/fixture (not in bootJar); server.port=0 avoids the standing :8081.
call "%~dp0..\gradlew.bat" i343d1Fixture -Pi343d1Command=%CMD% -Pi343d1Count=%COUNT%
exit /b %ERRORLEVEL%
