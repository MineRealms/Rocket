@echo off
setlocal
set "JAVA_HOME=%LOCALAPPDATA%\Programs\Temurin\jdk-17.0.21+9"
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo Java 17 was not found at "%JAVA_HOME%".
  exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "JAVA_TOOL_OPTIONS=-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=15236 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=15236"
call "%~dp0gradlew.bat" runClient
exit /b %ERRORLEVEL%
