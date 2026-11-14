@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\FoodieGo.ps1" -Action Stop
set "result=%errorlevel%"
if "%result%"=="0" (
    echo.
    echo FoodieGo stopped.
) else (
    echo.
    echo FoodieGo failed to stop. Exit code: %result%
)
pause
exit /b %result%
