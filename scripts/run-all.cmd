@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-all.ps1" %*
pause
