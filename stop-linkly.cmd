@echo off
setlocal
title Stop Linkly

rem Stop only this project's containers. Keep the database volume for the next demo.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop-linkly.ps1"
set "linklyExit=%ERRORLEVEL%"

if /I not "%~1"=="--no-pause" pause
exit /b %linklyExit%
