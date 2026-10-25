@echo off
setlocal EnableDelayedExpansion
set "PROJ_DIR=%~dp0"
set "JAVA_HOME=%PROJ_DIR%.toolchain\jdk"
set "ANDROID_HOME=%PROJ_DIR%.toolchain\android-sdk"
set "ANDROID_SDK_ROOT=%PROJ_DIR%.toolchain\android-sdk"
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%"

cd /d "%PROJ_DIR%"

echo ========================================================
echo   CamGuard Android - Instant Build ^& Run via ADB
echo ========================================================
echo.

"%ADB%" start-server >nul 2>&1

REM Check if any device/emulator is connected
set "DEV_COUNT=0"
for /f "skip=1 tokens=1,2" %%A in ('"%ADB%" devices') do (
    if "%%B"=="device" (
        set /a DEV_COUNT+=1
        set "CONNECTED_DEV=%%A"
    )
)

if !DEV_COUNT! EQU 0 (
    echo No Android phone or emulator detected via USB.
    echo.
    echo To connect your phone without manual APK installs:
    echo   Option A [USB]: Plug in your phone and enable "USB Debugging" in Developer Options.
    echo   Option B [Wi-Fi]: Enable "Wireless Debugging" in Developer Options, or enter IP:PORT below.
    echo.
    set /p WIFI_ADDR="Enter Phone Wi-Fi IP:Port (or press Enter after plugging in USB): "
    if not "!WIFI_ADDR!"=="" (
        "%ADB%" connect !WIFI_ADDR!
    )
)

echo.
echo [1/3] Building incremental APK...
call gradlew.bat assembleRelease --no-daemon
if errorlevel 1 (
    echo [ERROR] Build failed.
    pause
    exit /b 1
)

copy /Y "%USERPROFILE%\.android\camguard-build\app\outputs\apk\release\app-release.apk" "CamGuard-Android.apk" >nul

echo [2/3] Pushing ^& updating app silently on device (keeps your saved cameras)...
"%ADB%" install -r "CamGuard-Android.apk"
if errorlevel 1 (
    echo [ERROR] Could not install on device. Check USB/Wireless debugging prompt on phone.
    pause
    exit /b 1
)

echo [3/3] Launching CamGuard on your screen...
"%ADB%" shell am force-stop com.camguard.android
"%ADB%" shell am start -n com.camguard.android/.MainActivity

echo.
echo [DONE] App is now running on your device!
pause
