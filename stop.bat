@echo off
rem ============================================================
rem  WikiKernel 停止脚本：结束监听指定端口的进程
rem  用法：stop.bat [端口] [nopause]
rem ============================================================
chcp 65001 >nul

set "PORT=18080"
if not "%~1"=="" set "PORT=%~1"

set "FOUND="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":%PORT% " ^| findstr "LISTENING"') do (
    set "FOUND=1"
    taskkill /PID %%p /F >nul 2>nul
    if not errorlevel 1 echo [WikiKernel] 已停止进程 PID %%p（端口 %PORT%）
)
if not defined FOUND echo [WikiKernel] 端口 %PORT% 上没有正在运行的 WikiKernel 进程。

rem 清理 run.bat 启动时复制的启动副本（run\.launch\wikikernel-<端口>.jar）
if exist "%~dp0run\.launch\wikikernel-%PORT%.jar" del /q "%~dp0run\.launch\wikikernel-%PORT%.jar" >nul 2>nul

if /i "%~2"=="nopause" exit /b 0
pause
