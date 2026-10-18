@echo off
setlocal

rem Keep paths quoted so setup also works from folders containing spaces.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-linkly.ps1"
set "linklyExit=%ERRORLEVEL%"

if /I not "%~1"=="--no-pause" pause
exit /b %linklyExit%
