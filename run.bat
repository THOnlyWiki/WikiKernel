@echo off
rem ============================================================
rem  WikiKernel 启动脚本：打开一个可见的 cmd 窗口显示日志
rem  用法：双击运行；或 run.bat [端口]（默认 18080）
rem  日志：本窗口控制台 + run\logs\wikikernel.log（滚动归档）
rem  停止：Ctrl+C，或运行 stop.bat
rem
rem  安全启动：先把构建产物复制到 run\.launch\ 再运行副本。
rem  直接运行 core\build\libs 下的 jar 时，若期间又执行了 gradlew bootJar，
rem  jar 会被覆盖，正在运行的 JVM 按需加载 class（如 logback 的 ThrowableProxy、
rem  Tomcat 记录 socket 错误时才会用到）会读到被替换的文件，报
rem  NoClassDefFoundError / ClassNotFoundException 之类的随机错误。
rem ============================================================
chcp 65001 >nul
cd /d "%~dp0"

set "PORT=18080"
if not "%~1"=="" set "PORT=%~1"

set "JAR=core\build\libs\core-0.0.1-SNAPSHOT.jar"
if not exist "%JAR%" (
    echo [WikiKernel] 未找到 %JAR%
    echo [WikiKernel] 请先构建：gradlew.bat :core:bootJar
    pause
    exit /b 1
)

set "LAUNCH_DIR=run\.launch"
set "RUN_JAR=%LAUNCH_DIR%\wikikernel-%PORT%.jar"
if not exist "%LAUNCH_DIR%" mkdir "%LAUNCH_DIR%" >nul 2>nul
copy /y "%JAR%" "%RUN_JAR%" >nul
if errorlevel 1 (
    echo [WikiKernel] 复制启动副本失败：%JAR% -^> %RUN_JAR%
    echo [WikiKernel] 如果正在构建（gradlew bootJar），请等构建完成后再启动。
    pause
    exit /b 1
)

title WikiKernel - 端口 %PORT%
echo ============================================================
echo   WikiKernel 正在启动（端口 %PORT%）
echo   启动副本：%RUN_JAR%（构建覆盖 core\build 下的 jar 不影响本进程）
echo   控制台日志 + 文件日志：run\logs\wikikernel.log
echo   停止：Ctrl+C 或运行 stop.bat
echo ============================================================
echo.

java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%RUN_JAR%" --server.port=%PORT%

echo.
echo [WikiKernel] 进程已退出，窗口保留（可直接关闭）。
pause
