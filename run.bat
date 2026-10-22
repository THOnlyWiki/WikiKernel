@echo off
rem ============================================================
rem  WikiKernel launcher (visible console + rolling log)
rem  Usage: run.bat [port]   (default: 18080)
rem  Stop: Ctrl+C, or run stop.bat
rem
rem  Preferred jar: wikikernel-server.jar next to this script
rem  Source tree : core\build\libs\core-0.0.1-SNAPSHOT.jar
rem
rem  The launch jar contains the Mixin agent and uses the JDK
rem  Launcher-Agent-Class + Spring Boot PropertiesLauncher,
rem  so `java -jar` also works without a separate -javaagent.
rem ============================================================
chcp 65001 >nul
cd /d "%~dp0"

set "PORT=18080"
if not "%~1"=="" set "PORT=%~1"

set "JAR="
if exist "%~dp0wikikernel-server.jar" set "JAR=%~dp0wikikernel-server.jar"
if not defined JAR set "JAR=core\build\libs\core-0.0.1-SNAPSHOT.jar"
if not exist "%JAR%" (
    echo [WikiKernel] Launcher jar not found: %JAR%
    echo [WikiKernel] Build it with: gradlew.bat dist
    echo [WikiKernel] Or only core: gradlew.bat :core:bootJar
    pause
    exit /b 1
)

set "LAUNCH_DIR=run\.launch"
set "RUN_JAR=%LAUNCH_DIR%\wikikernel-%PORT%.jar"
if not exist "%LAUNCH_DIR%" mkdir "%LAUNCH_DIR%" >nul 2>nul
copy /y "%JAR%" "%RUN_JAR%" >nul
if errorlevel 1 (
    echo [WikiKernel] Failed to copy launcher jar: %JAR% -^> %RUN_JAR%
    echo [WikiKernel] If a build is running, wait for it to finish.
    pause
    exit /b 1
)

title WikiKernel - port %PORT%
echo ============================================================
echo   WikiKernel starting on port %PORT%
echo   Launcher copy: %RUN_JAR%
echo   Mixin agent: embedded in launcher jar
echo   Console log + file log: run\logs\wikikernel.log
echo   Stop: Ctrl+C or stop.bat
echo ============================================================
echo.

java --sun-misc-unsafe-memory-access=allow -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%RUN_JAR%" --server.port=%PORT%

echo.
echo [WikiKernel] Process exited; this window is kept open.
pause
