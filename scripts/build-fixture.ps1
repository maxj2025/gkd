. "$PSScriptRoot/qingping-env.ps1"
$qingRoot = Split-Path $PSScriptRoot -Parent
$qingOut = New-Item -ItemType Directory -Force -Path "$qingRoot/.local/qa"
$qingClasses = New-Item -ItemType Directory -Force -Path "$qingOut/classes"
$qingTools = "$env:ANDROID_HOME/build-tools/37.0.0"
$qingPlatform = "$env:ANDROID_HOME/platforms/android-36/android.jar"
& "$qingTools/aapt2.exe" link -I $qingPlatform --manifest "$qingRoot/qa/fixture/AndroidManifest.xml" -o "$qingOut/fixture-base.apk"
if ($LASTEXITCODE -ne 0) { throw 'fixture resource linking failed' }
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -source 11 -target 11 -classpath $qingPlatform -d $qingClasses "$qingRoot/qa/fixture/AdActivity.java"
if ($LASTEXITCODE -ne 0) { throw 'fixture Java compilation failed' }
$qingClassFiles = @(Get-ChildItem $qingClasses -Filter '*.class' -Recurse | Select-Object -ExpandProperty FullName)
& "$qingTools/d8.bat" --lib $qingPlatform --min-api 26 --output $qingOut @qingClassFiles
if ($LASTEXITCODE -ne 0) { throw 'fixture dex compilation failed' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$qingArchive = [System.IO.Compression.ZipFile]::Open("$qingOut/fixture-base.apk", [System.IO.Compression.ZipArchiveMode]::Update)
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($qingArchive, "$qingOut/classes.dex", 'classes.dex') | Out-Null
} finally { $qingArchive.Dispose() }
if (-not (Test-Path "$qingOut/test.jks")) {
    & "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -keystore "$qingOut/test.jks" -alias test -keyalg RSA -validity 1000 -dname 'CN=Qingping Test Only' -storepass android -keypass android
    if ($LASTEXITCODE -ne 0) { throw 'fixture key generation failed' }
}
& "$qingTools/zipalign.exe" -f 4 "$qingOut/fixture-base.apk" "$qingOut/fixture-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'fixture zipalign failed' }
& "$qingTools/apksigner.bat" sign --ks "$qingOut/test.jks" --ks-key-alias test --ks-pass pass:android --out "$qingOut/fixture.apk" "$qingOut/fixture-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'fixture signing failed' }
