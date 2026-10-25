@echo off
setlocal
set "PROJ_DIR=%~dp0"
set "JAVA_HOME=%PROJ_DIR%.toolchain\jdk"
set "ANDROID_HOME=%PROJ_DIR%.toolchain\android-sdk"
set "ANDROID_SDK_ROOT=%PROJ_DIR%.toolchain\android-sdk"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%"

cd /d "%PROJ_DIR%"
echo Building V380Protect-Android.apk ...
call gradlew.bat assembleRelease --no-daemon
if exist "%USERPROFILE%\.android\v380-build\app\outputs\apk\release\app-release.apk" (
    copy /Y "%USERPROFILE%\.android\v380-build\app\outputs\apk\release\app-release.apk" "V380Protect-Android.apk" >nul
    echo.
    echo [SUCCESS] Built V380Protect-Android.apk
) else (
    echo [ERROR] Build failed.
)
pause
