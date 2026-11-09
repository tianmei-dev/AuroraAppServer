@echo off
REM Isolated server: port 8081 + aurora_app_test (3307) + FakePaymentProvider.
REM Runs from the fixture classpath (src/fixture); never the production jar.
REM Does NOT use application-local.yml / 8080 / aurora_app (3308). Never calls the real Yusen gateway.

set "GRADLE_USER_HOME=D:\Android_Work_Server\.gradle-home"
set "TEMP=D:\Android_Work_Server\.tmp"
set "TMP=D:\Android_Work_Server\.tmp"
if not exist "%TEMP%" mkdir "%TEMP%"
if not exist "%GRADLE_USER_HOME%" mkdir "%GRADLE_USER_HOME%"

cd /d "%~dp0"
echo GRADLE_USER_HOME=%GRADLE_USER_HOME%
echo Starting isolated s32it server on :8081 (DB 3307 aurora_app_test, payment provider FAKE)
call "%~dp0gradlew.bat" s32itServer %*
