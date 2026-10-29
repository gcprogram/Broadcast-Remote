# Builds broadcast-remote.apk without Gradle (aapt2 + javac + d8 + apksigner). Usage: powershell -File build.ps1
$ErrorActionPreference = "Stop"
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = "$sdk\build-tools\35.0.1"
$jar = "$sdk\platforms\android-35\android.jar"
$here = $PSScriptRoot
$out = "$here\build"
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\classes", "$out\dex" | Out-Null

& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$here\AndroidManifest.xml" --min-sdk-version 26 --target-sdk-version 34 -A "$here\assets"
if ($LASTEXITCODE) { throw "aapt2 link failed" }

$src = Get-ChildItem "$here\src" -Recurse -Filter *.java | ForEach-Object FullName
& javac --release 8 -encoding UTF-8 -cp $jar -d "$out\classes" $src
if ($LASTEXITCODE) { throw "javac failed" }

$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --lib $jar --min-api 26 --output "$out\dex" $classes
if ($LASTEXITCODE) { throw "d8 failed" }

python -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'a'); z.write(sys.argv[2],'classes.dex'); z.close()" "$out\base.apk" "$out\dex\classes.dex"
& "$bt\zipalign.exe" -f -p 4 "$out\base.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw "zipalign failed" }

$ks = "$here\debug.keystore"
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -keystore $ks -storepass android -keypass android -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Broadcast-Remote debug"
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out "$here\broadcast-remote.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw "apksigner failed" }
"Done: $here\broadcast-remote.apk"
