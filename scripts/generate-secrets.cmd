@echo off
setlocal

rem Generate independent 48-byte secrets using Windows' cryptographic random generator.
rem Print them only: never overwrite .env or rotate an existing installation's keys.
echo Linkly signing secrets
echo Copy the following two lines into your private .env file:
echo.

powershell.exe -NoLogo -NoProfile -NonInteractive -Command "$ErrorActionPreference = 'Stop'; $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create(); try { foreach ($name in @('JWT_SECRET', 'VERIFICATION_SECRET')) { $bytes = New-Object byte[] 48; $rng.GetBytes($bytes); Write-Output ($name + '=' + [Convert]::ToBase64String($bytes)) } } finally { $rng.Dispose() }"
if errorlevel 1 (
    echo.
    echo Failed to generate secrets. Check that Windows PowerShell is available.
    if /I not "%~1"=="--no-pause" pause
    exit /b 1
)

echo.
echo Keep these values private. Each run generates new values.
echo Existing installations: replacing keys invalidates old login and suggestion tokens.
if /I not "%~1"=="--no-pause" pause
exit /b 0
