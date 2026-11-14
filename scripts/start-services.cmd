@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Start-FoodieGo-Services.ps1"
if errorlevel 1 pause
