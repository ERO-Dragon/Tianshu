@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo.
echo ============================================================
echo   Tianshu adapter smoke run: every client, one after another
echo ============================================================
echo   results : build\smoke\summary.txt
echo   logs    : build\smoke\<adapter>\gradle.log / client.log
echo.
echo   Pass adapter names with -Only, e.g.
echo     smoke-adapters.bat -Only 1.20.1-fabric,1.20.1-neoforge
echo   Other switches: -Mode online^|offline^|auto  -TimeoutSeconds N  -Force
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0smoke-adapters.ps1" %*
echo.
echo Done. Summary: build\smoke\summary.txt
pause