@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Stop-FoodieGo-Services.ps1"
if errorlevel 1 pause
