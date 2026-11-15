@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-ferry.ps1" %*
exit /b %ERRORLEVEL%