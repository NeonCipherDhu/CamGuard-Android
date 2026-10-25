@echo off
setlocal EnableDelayedExpansion
set "PROJ_DIR=%~dp0"
set "TC=%PROJ_DIR%.toolchain"
set "JAVA_HOME=%TC%\jdk"
set "SCRCPY=%TC%\scrcpy\scrcpy.exe"

REM Ensure space-free junction paths for Android SDK & AVD
set "SDK_LINK=%USERPROFILE%\.android\v380-sdk"
set "AVD_LINK=%USERPROFILE%\.android\v380-avd"
if not exist "!SDK_LINK!" mklink /J "!SDK_LINK!" "%TC%\android-sdk" >nul 2>&1
if not exist "!AVD_LINK!" mklink /J "!AVD_LINK!" "%TC%\avd" >nul 2>&1

set "ANDROID_HOME=!SDK_LINK!"
set "ANDROID_SDK_ROOT=!SDK_LINK!"
set "ANDROID_AVD_HOME=!AVD_LINK!"
set "ADB=!SDK_LINK!\platform-tools\adb.exe"
set "EMULATOR=!SDK_LINK!\emulator\emulator.exe"
set "PATH=%JAVA_HOME%\bin;!SDK_LINK!\platform-tools;!SDK_LINK!\emulator;%PATH%"

cd /d "%PROJ_DIR%"

echo ========================================================
echo   V380 Protect Android - PC Emulator Launcher
echo ========================================================
echo.

"%ADB%" start-server >nul 2>&1

REM Check if an emulator is already running
set "EMU_ID="
for /f "skip=1 tokens=1,2" %%A in ('"%ADB%" devices') do (
    echo %%A | findstr /b "emulator-" >nul
    if not errorlevel 1 (
        if "%%B"=="device" set "EMU_ID=%%A"
    )
)

if "!EMU_ID!"=="" (
    echo [1/4] Starting V380_Phone Android Emulator...
    start "V380 Phone Emulator" "!EMULATOR!" -avd V380_Phone -gpu host -no-snapshot-save -netdelay none -netspeed full

    echo Waiting for Android Emulator to boot...
    "%ADB%" wait-for-device
    :wait_boot
    for /f "tokens=*" %%S in ('"%ADB%" shell getprop sys.boot_completed 2^>nul') do set "BOOTED=%%S"
    if not "!BOOTED!"=="1" (
        timeout /t 2 /nobreak >nul
        goto wait_boot
    )
    for /f "skip=1 tokens=1,2" %%A in ('"%ADB%" devices') do (
        echo %%A | findstr /b "emulator-" >nul
        if not errorlevel 1 set "EMU_ID=%%A"
    )
    echo Emulator booted (!EMU_ID!)!
) else (
    echo [1/4] Emulator already running (!EMU_ID!)!
)

REM Forward V380 UDP discovery response port 10009 into Emulator
"%ADB%" -s !EMU_ID! emu redir add udp:10009:10009 >nul 2>&1

echo.
echo [2/4] Building latest V380Protect-Android.apk...
call gradlew.bat assembleDebug --no-daemon
if errorlevel 1 (
    echo [ERROR] Build failed.
    pause
    exit /b 1
)
copy /Y "%USERPROFILE%\.android\v380-build\app\outputs\apk\debug\app-debug.apk" "V380Protect-Android.apk" >nul

echo [3/4] Installing / updating app in Emulator...
"%ADB%" -s !EMU_ID! install -r "V380Protect-Android.apk"

echo [4/4] Launching V380 Protect...
"%ADB%" -s !EMU_ID! shell am force-stop com.v380protect.android
"%ADB%" -s !EMU_ID! shell am start -n com.v380protect.android/.MainActivity

echo.
echo [SUCCESS] V380 Protect is now running in the phone window!
pause
