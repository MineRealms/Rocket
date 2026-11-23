@echo off
setlocal
title Minecraft 1.20.1 - Rocket
set "ROCKET_DIR=%~dp0forge-1.20.1-47.4.29-mdk"

if not exist "%ROCKET_DIR%\run-client.bat" (
  echo [ERROR] The Rocket Minecraft launcher could not be found.
  echo Expected: "%ROCKET_DIR%\run-client.bat"
  pause
  exit /b 1
)

pushd "%ROCKET_DIR%"
if errorlevel 1 (
  echo [ERROR] Could not open the Rocket Minecraft project folder.
  pause
  exit /b 1
)

echo Starting Minecraft 1.20.1 - Rocket...
echo Keep the local proxy at 127.0.0.1:15236 running for dependency downloads.
echo The game window will open after Gradle finishes preparing it.
call "%ROCKET_DIR%\run-client.bat"
set "ROCKET_EXIT_CODE=%ERRORLEVEL%"
popd

if not "%ROCKET_EXIT_CODE%"=="0" (
  echo.
  echo [ERROR] Minecraft exited with code %ROCKET_EXIT_CODE%.
  echo See the output above and "%ROCKET_DIR%\run\logs\latest.log".
  pause
)

exit /b %ROCKET_EXIT_CODE%
