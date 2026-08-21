@echo off
chcp 65001 >nul
setlocal

rem Java 17 路径
set JAVA_HOME=C:\Users\ZhuYixin\.jdks\ms-17.0.16
set JAVA=%JAVA_HOME%\bin\java.exe

rem 检查 Java 是否可用
if not exist "%JAVA%" (
    echo [ERROR] 找不到 Java: %JAVA%
    echo 请修改 run.bat 中的 JAVA_HOME 路径
    pause
    exit /b 1
)

rem 检查 API Key 配置
if "%LLM_API_KEY%"=="" (
    echo [WARNING] 未设置环境变量 LLM_API_KEY
    echo 请设置后重新运行:
    echo   set LLM_API_KEY=your_api_key
    echo.
    echo 或者通过命令行参数传递:
    echo   run.bat -Dllm.api.key=your_api_key
    echo.
)

rem 设置默认参数
set JAR_FILE=target\agentic-coding-agent-1.0-SNAPSHOT.jar

if not exist "%JAR_FILE%" (
    echo [ERROR] 找不到 JAR 文件: %JAR_FILE%
    echo 请先运行: mvn package
    pause
    exit /b 1
)

rem 默认项目根：与 agentic-coding-agent 同级的 agent-workspace
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "DEFAULT_WORKSPACE=%%~fI\agent-workspace"
if not exist "%DEFAULT_WORKSPACE%" (
    mkdir "%DEFAULT_WORKSPACE%"
    echo 已创建默认工作目录: %DEFAULT_WORKSPACE%
)

rem 启动应用
echo 正在启动 Agentic Coding Agent...
echo 项目根: %DEFAULT_WORKSPACE%
echo.

"%JAVA%" %* -Dfile.encoding=UTF-8 -Dworkspace.path="%DEFAULT_WORKSPACE%" -jar "%JAR_FILE%"

endlocal
