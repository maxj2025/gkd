param([switch]$SkipTests)
. "$PSScriptRoot/qingping-env.ps1"
$qingRoot = Split-Path $PSScriptRoot -Parent
Push-Location $qingRoot
try {
    $qingSigning = New-Item -ItemType Directory -Force -Path .local/signing
    $qingPasswordFile = Join-Path $qingSigning.FullName 'password.txt'
    if (-not (Test-Path -LiteralPath $qingPasswordFile)) {
        Set-Content -LiteralPath $qingPasswordFile -Value ([guid]::NewGuid().ToString('N')) -NoNewline -Encoding ascii
    }
    $env:QINGPING_STORE_PASS = Get-Content -LiteralPath $qingPasswordFile -Raw
    $qingKeyFile = Join-Path $qingSigning.FullName 'qingping.jks'
    if (-not (Test-Path -LiteralPath $qingKeyFile)) {
        & "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -keystore $qingKeyFile -alias qingping -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Qingping Personal Build' -storepass:env QINGPING_STORE_PASS -keypass:env QINGPING_STORE_PASS
        if ($LASTEXITCODE -ne 0) { throw '签名密钥创建失败' }
    }
    $env:ORG_GRADLE_PROJECT_GKD_STORE_FILE = $qingKeyFile
    $env:ORG_GRADLE_PROJECT_GKD_STORE_PASSWORD = $env:QINGPING_STORE_PASS
    $env:ORG_GRADLE_PROJECT_GKD_KEY_ALIAS = 'qingping'
    $env:ORG_GRADLE_PROJECT_GKD_KEY_PASSWORD = $env:QINGPING_STORE_PASS
    $qingTasks = @(':app:assembleGkdRelease')
    if (-not $SkipTests) { $qingTasks += @(':app:testGkdDebugUnitTest', ':app:lintGkdRelease') }
    & ./gradlew.bat @qingTasks --no-watch-fs --max-workers=2 --console=plain
    if ($LASTEXITCODE -ne 0) { throw '构建或检查未通过' }
} finally {
    Remove-Item Env:QINGPING_STORE_PASS,Env:ORG_GRADLE_PROJECT_GKD_STORE_PASSWORD,Env:ORG_GRADLE_PROJECT_GKD_KEY_PASSWORD -ErrorAction SilentlyContinue
    Pop-Location
}
