@echo off
echo ========================================================
echo Installing Latest Jarvis APK to Connected Phone...
echo ========================================================
set ADB="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist %ADB% (
    echo Error: ADB not found at %ADB%
    pause
    exit /b 1
)

echo Checking connected devices...
%ADB% devices

echo Installing Jarvis-Latest-Debug.apk...
%ADB% install -r -d "%~dp0Jarvis-Latest-Debug.apk"

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================================
    echo [SUCCESS] Jarvis APK installed successfully on your phone!
    echo ========================================================
) else (
    echo.
    echo [NOTE] If installation failed:
    echo 1. Ensure USB Debugging is turned ON in Developer Options.
    echo 2. Check your phone screen and tap 'Allow USB debugging'.
    echo 3. Make sure USB mode is set to 'File Transfer' (MTP).
)
pause
