@echo off
REM I3.4.3-D1 membership isolation fixture CLI (s32it + aurora_app_test only).
REM Usage:
REM   scripts\i343d1-fixture.bat seed
REM   scripts\i343d1-fixture.bat clean
REM Tokens are written to build\i343d1-sessions.local.json (gitignored). Never commit or paste them.

set "GRADLE_USER_HOME=D:\Android_Work_Server\.gradle-home"
set "TEMP=D:\Android_Work_Server\.tmp"
set "TMP=D:\Android_Work_Server\.tmp"
if not exist "%TEMP%" mkdir "%TEMP%"

cd /d "%~dp0.."

if "%~1"=="" (
  echo Usage: %~nx0 seed^|clean
  exit /b 2
)

set "CMD=%~1"
echo Running I3.4.3-D1 fixture command=%CMD% (profile=s32it, server.port=0)
REM Fixture classes live in src/fixture (not in bootJar); server.port=0 avoids the standing :8081.
call "%~dp0..\gradlew.bat" i343d1Fixture -Pi343d1Command=%CMD%
exit /b %ERRORLEVEL%
