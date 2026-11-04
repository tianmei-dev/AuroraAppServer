@echo off
REM Isolated S3.2 / I3.2-A instance: port 8081 + aurora_app_test (3307).
REM Does NOT use application-local.yml / 8080 / aurora_app (3308).

set "GRADLE_USER_HOME=D:\Android_Work_Server\.gradle-home"
set "TEMP=D:\Android_Work_Server\.tmp"
set "TMP=D:\Android_Work_Server\.tmp"
if not exist "%TEMP%" mkdir "%TEMP%"
if not exist "%GRADLE_USER_HOME%" mkdir "%GRADLE_USER_HOME%"

cd /d "%~dp0"
set "SPRING_PROFILES_ACTIVE=s32it"
echo GRADLE_USER_HOME=%GRADLE_USER_HOME%
echo SPRING_PROFILES_ACTIVE=%SPRING_PROFILES_ACTIVE%
echo Starting isolated S3.2 IT instance on :8081 (DB 3307 aurora_app_test)
call "%~dp0gradlew.bat" bootRun --args="--spring.profiles.active=s32it" %*
