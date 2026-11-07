@echo off
setlocal
cd /d "%~dp0"
call gradlew.bat :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease --console=plain
if errorlevel 1 exit /b 1
if not exist artifacts mkdir artifacts
copy /y "app\build\outputs\apk\release\app-release.apk" "artifacts\Folio-1.0.1.apk"
echo.
echo Folio is ready in artifacts\Folio-1.0.1.apk
endlocal
