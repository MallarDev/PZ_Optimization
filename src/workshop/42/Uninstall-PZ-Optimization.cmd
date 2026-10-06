@echo off
rem PZ Optimization (its Steam Workshop folder): double-click to remove it from the game folder. The game must be closed
rem (the script waits for it). Runs install.ps1 beside this file with -Uninstall.
title Uninstall PZ Optimization
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" -Uninstall -Pause
