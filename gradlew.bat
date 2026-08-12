@echo off
where gradle >nul 2>nul
if %errorlevel%==0 (
  gradle %*
) else (
  echo Gradle is not installed. Open this project in Android Studio or use GitHub Actions.
  exit /b 1
)
