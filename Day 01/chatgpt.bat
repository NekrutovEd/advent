@echo off
chcp 65001 >nul
title ChatGPT Console

where kotlin >nul 2>nul
if %errorlevel% neq 0 (
    echo.
    echo  Kotlin CLI not found!
    echo  Install: https://kotlinlang.org/docs/command-line.html
    echo  Or via SDKMAN: sdk install kotlin
    echo  Or via Scoop:  scoop install kotlin
    echo.
    pause
    exit /b 1
)

kotlin "%~dp0chatgpt.main.kts"
pause
