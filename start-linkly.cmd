@echo off
setlocal
title Linkly

rem Keep paths quoted so setup also works from folders containing spaces.
rem Keep the existing one-shot --no-pause mode for automation and setup tests.
if /I "%~1"=="--no-pause" (
    powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-linkly.ps1"
) else (
    powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-linkly.ps1" -FollowLogs
)
set "linklyExit=%ERRORLEVEL%"

rem Keep errors visible; a normal stop ends the log stream and closes the window.
if not "%linklyExit%"=="0" if /I not "%~1"=="--no-pause" pause
exit /b %linklyExit%
