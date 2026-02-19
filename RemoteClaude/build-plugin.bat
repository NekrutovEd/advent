@echo off
cd /d "%~dp0plugin"
call gradlew.bat buildPlugin
if %ERRORLEVEL% neq 0 (
    echo Build failed.
    pause
    exit /b %ERRORLEVEL%
)
echo.
echo Build successful: plugin\build\distributions\remoteclaude-plugin-1.0.0.zip
pause
