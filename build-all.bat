@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0"

rem ---------------------------------------------------------------------------
rem  天枢 · 一键全量编译：10 个模块 + tianshu-adapter 下全部 43 个版本包
rem
rem  用途：开了梯子之后，把整张矩阵配置并编译一遍。重点观察 1.20.2 / 1.20.3 /
rem        1.20.5 三个 NeoGradle 包：它们在配置期就解析 net.neoforged:neoforge，
rem        仓库连不上时整次配置失败，报出来却是 "The run type 'client' was not
rem        found" 或某个 log4j 2.11.+。
rem
rem  用法：build-all.bat                    在线编译（默认，结束时按任意键关闭）
rem        build-all.bat --offline          只用已有缓存编译，完全不碰网络
rem        build-all.bat --no-pause         不等待按键（在脚本或终端里调用）
rem        set TIANSHU_WORKERS=4            改并行度（默认 2；43 个适配器并行会撑爆 3G 堆）
rem ---------------------------------------------------------------------------

set "MODE=online"
set "LOG=build\build-all-online.log"
set "ARGS=--console=plain --continue"
if not defined TIANSHU_WORKERS set "TIANSHU_WORKERS=2"
set "ARGS=%ARGS% --max-workers=%TIANSHU_WORKERS%"
set "NOPAUSE="

:parse
if "%~1"=="" goto parsed
if /i "%~1"=="--offline" (
    set "MODE=offline"
    set "LOG=build\build-all-offline.log"
    set "ARGS=%ARGS% --offline"
)
if /i "%~1"=="--no-pause" set "NOPAUSE=1"
shift
goto parse
:parsed

if not exist "build" mkdir "build"
if exist "%LOG%" del /q "%LOG%"

echo.
echo ============================================================
echo   天枢 · 全量编译      模式: %MODE%      并行: %TIANSHU_WORKERS%
echo ============================================================
echo   tianshu-api / core / client / testkit / lab
echo   intake / echo / weaving / auxilium / presence
echo   tianshu-adapter 下全部 43 个版本包（21 NeoForge + 22 Fabric）
echo.
if "%MODE%"=="online" (
    echo   前提：梯子已开。Java 不走系统代理，需要 TUN / 全局模式，
    echo         或在 gradle.properties 里加
    echo         systemProp.https.proxyHost / systemProp.https.proxyPort。
    echo.
)

echo   [%DATE% %TIME%] gradlew %ARGS% compileJava
echo   编译中，可能需要几分钟（首次配置新版本更久）；输出同时写入
echo   %LOG%，完成后在这里显示结果。
echo.
>"%LOG%" echo [%DATE% %TIME%] gradlew %ARGS% compileJava
call gradlew.bat %ARGS% compileJava >> "%LOG%" 2>&1
set "RESULT=%ERRORLEVEL%"

echo.
echo ---------------------------- 结果 ----------------------------
rem 两类失败都抓：任务级（编译错）与项目级（build 脚本求值或配置错——1.20.2/3/5
rem 在网络不通时就栽在这一步；同一个项目会各报一条，排序后按项目名去重）。
set "TASKFAILS=%TEMP%\tianshu-task-fails.txt"
set "CFGRAW=%TEMP%\tianshu-cfg-raw.txt"
set "CFGNAMES=%TEMP%\tianshu-cfg-names.txt"
set "CFGDEDUP=%TEMP%\tianshu-cfg-dedup.txt"
del /q "%TASKFAILS%" "%CFGRAW%" "%CFGNAMES%" "%CFGDEDUP%" 2>nul
findstr /C:":compileJava FAILED" "%LOG%" > "%TASKFAILS%" 2>nul
findstr /R /C:"A problem occurred .*project ':" "%LOG%" > "%CFGRAW%" 2>nul

set /a TASKCOUNT=0
for /f "usebackq delims=" %%f in ("%TASKFAILS%") do set /a TASKCOUNT+=1

set /a CFGCOUNT=0
if exist "%CFGRAW%" (
    for /f "usebackq tokens=6 delims= " %%f in ("%CFGRAW%") do >>"%CFGNAMES%" echo %%f
    if exist "%CFGNAMES%" (
        sort "%CFGNAMES%" /O "%CFGNAMES%"
        set "PREV="
        for /f "usebackq delims=" %%f in ("%CFGNAMES%") do (
            set "P=%%f"
            set "P=!P:'.=!"
            set "P=!P:'=!"
            if not "!P!"=="!PREV!" (
                set /a CFGCOUNT+=1
                >>"%CFGDEDUP%" echo !P!
                set "PREV=!P!"
            )
        )
    )
)

if %TASKCOUNT%==0 if %CFGCOUNT%==0 goto passed

if not %TASKCOUNT%==0 (
    echo   编译失败 %TASKCOUNT% 个：
    for /f "usebackq delims=" %%f in ("%TASKFAILS%") do echo     %%f
)
if not %CFGCOUNT%==0 (
    echo   项目级失败 %CFGCOUNT% 个（build 脚本求值或配置，多为仓库连不上）：
    for /f "usebackq delims=" %%f in ("%CFGDEDUP%") do echo     %%f
)
echo.
findstr /C:"Java heap space" "%LOG%" >nul && echo   提示：Java heap space 是内存不足；关掉其它进程，或 set TIANSHU_WORKERS=1 重跑。
findstr /C:"disabled due to earlier error" "%LOG%" >nul && echo   提示：某仓库连不上被 Gradle 禁用，随后解析失败——1.20.2/3/5 的典型报错。检查梯子是否 TUN/全局，或配 systemProp.https.proxyHost。
findstr /C:"Could not resolve" "%LOG%" >nul && echo   提示：依赖解析失败，通常仍是网络或代理问题；只验证缓存请用 build-all.bat --offline。
goto summary

:passed
echo   通过：全部模块与 43 个版本包。

:summary
echo.
echo   完整日志: %LOG%
if not "%RESULT%"=="0" echo   退出码: %RESULT%
echo.

if not defined NOPAUSE pause
exit /b %RESULT%