$ErrorActionPreference = 'Stop'
$qingRoot = Split-Path $PSScriptRoot -Parent
$qingJdk = if (Test-Path -LiteralPath "$qingRoot/.local/tools") {
    Get-ChildItem -LiteralPath "$qingRoot/.local/tools" -Directory -Filter 'jdk-21*' | Select-Object -First 1
}
if ($qingJdk) { $env:JAVA_HOME = $qingJdk.FullName }
if (-not $env:JAVA_HOME) { throw '请设置 JAVA_HOME 为 JDK 21 或更新版本' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA/Android/Sdk" }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$qingTmp = New-Item -ItemType Directory -Force -Path "$qingRoot/.local/tmp"
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$($qingTmp.FullName)"
$env:Path = "$env:JAVA_HOME/bin;$env:ANDROID_HOME/platform-tools;$env:Path"
