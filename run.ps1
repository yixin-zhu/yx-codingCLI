# Agentic Coding Agent 启动脚本 (PowerShell)
# 使用方法: .\run.ps1 [-ApiKey your_key] [-Model model_name]

param(
    [string]$ApiKey = $env:LLM_API_KEY,
    [string]$Model = $env:LLM_MODEL,
    [string]$ApiUrl = $env:LLM_API_URL
)

# 设置 Java 17 路径
$env:JAVA_HOME = "C:\Users\ZhuYixin\.jdks\ms-17.0.16"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# 检查 Java
try {
    $javaVersion = & java -version 2>&1
    Write-Host "Java 版本: $javaVersion"
} catch {
    Write-Host "[ERROR] 找不到 Java，请确保已安装 Java 17+"
    exit 1
}

# 检查 API Key
if (-not $ApiKey) {
    Write-Host "[WARNING] 未设置 API Key"
    Write-Host " 方式1: 设置环境变量"
    Write-Host '    $env:LLM_API_KEY = "your_api_key"'
    Write-Host ""
    Write-Host " 方式2: 通过参数传递"
    Write-Host '    .\run.ps1 -ApiKey your_api_key'
    Write-Host ""
}

# 默认项目根：与 agentic-coding-agent 同级的 agent-workspace
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$DefaultWorkspace = Join-Path (Split-Path -Parent $ScriptDir) "agent-workspace"
if (-not (Test-Path $DefaultWorkspace)) {
    New-Item -ItemType Directory -Path $DefaultWorkspace | Out-Null
    Write-Host "已创建默认工作目录: $DefaultWorkspace"
}

# 构建 JVM 参数
$jvmArgs = @()
$jvmArgs += "-Dworkspace.path=$DefaultWorkspace"

if ($ApiKey) {
    $jvmArgs += "-Dllm.api.key=$ApiKey"
}
if ($Model) {
    $jvmArgs += "-Dllm.model=$Model"
}
if ($ApiUrl) {
    $jvmArgs += "-Dllm.api.url=$ApiUrl"
}

# 设置编码
$jvmArgs += "-Dfile.encoding=UTF-8"

# JAR 文件路径
$jarFile = "target\agentic-coding-agent-1.0-SNAPSHOT.jar"

if (-not (Test-Path $jarFile)) {
    Write-Host "[ERROR] 找不到 JAR 文件: $jarFile"
    Write-Host "请先运行: mvn package"
    exit 1
}

# 启动
Write-Host "正在启动 Agentic Coding Agent..."
Write-Host ""

& java @jvmArgs -jar $jarFile
