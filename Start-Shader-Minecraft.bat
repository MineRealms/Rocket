@echo off
setlocal
title Minecraft 1.20.1 - RocketFlow
pushd "%~dp0"
if errorlevel 1 exit /b 1
python -B tools\launch_production_client.py --oculus --stage-mods --validation-bridge --world ROCKET-Shader-Test %*
set "ROCKET_EXIT_CODE=%ERRORLEVEL%"
popd
if not "%ROCKET_EXIT_CODE%"=="0" (
  echo [ERROR] Shader client preparation failed. See the output above.
  pause
)
exit /b %ROCKET_EXIT_CODE%
