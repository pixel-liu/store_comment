@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\FoodieGo.ps1" -Action Start
set "result=%errorlevel%"
if "%result%"=="0" (
    echo.
    echo FoodieGo started. The browser is opening http://127.0.0.1:8081/#/meals
) else (
    echo.
    echo FoodieGo failed to start. Exit code: %result%
)
pause
exit /b %result%
