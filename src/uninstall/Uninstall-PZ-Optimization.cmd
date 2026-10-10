@echo off
rem PZ Optimization: double-click to remove it from this game folder. The game must be closed (the script waits for it).
rem Runs the installer the release left in pzopt\uninstall\ with -Uninstall, else the latest one from GitHub.
title Uninstall PZ Optimization
set "PZOPT_GAME=%~dp0."
if exist "%~dp0pzopt\uninstall\install.ps1" (
  copy /y "%~dp0pzopt\uninstall\install.ps1" "%TEMP%\pzopt-uninstall.ps1" >nul
  powershell -NoProfile -ExecutionPolicy Bypass -File "%TEMP%\pzopt-uninstall.ps1" -Uninstall -Dir "%PZOPT_GAME%" -Pause
  exit /b
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "& ([scriptblock]::Create((irm 'https://github.com/xD3I/PZ_Optimization/releases/latest/download/install.ps1'))) -Uninstall -Dir $env:PZOPT_GAME -Pause"
exit /b
