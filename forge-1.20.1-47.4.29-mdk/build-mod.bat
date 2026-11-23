@echo off
setlocal
if defined ROCKET_JAVA_HOME set "JAVA_HOME=%ROCKET_JAVA_HOME%"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
pushd "%~dp0"
call gradlew.bat build %*
set "ROCKET_EXIT=%ERRORLEVEL%"
popd
exit /b %ROCKET_EXIT%
