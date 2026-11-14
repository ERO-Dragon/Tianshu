@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-workspace.ps1"
exit /b %ERRORLEVEL%