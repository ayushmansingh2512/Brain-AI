# ===========================================================
# Brain AI Assistant - Windows Installer Builder
# Run this in PowerShell: .\build-installer.ps1
# ===========================================================
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$BASE = $PSScriptRoot
$LIB = "$BASE\lib"
$SRC = "$BASE\src\main\java"
$RES = "$BASE\src\main\resources"
$BUILD_CLS = "$BASE\build\classes"
$DIST = "$BASE\dist"
$JAVAFX_SDK = "$BASE\javafx-sdk"
$RUNTIME = "$BASE\build\runtime"

Write-Host ""
Write-Host "============================================================"
Write-Host "  Brain AI Assistant - Windows Installer Builder"
Write-Host "============================================================"
Write-Host ""

# 0. Check toolchain
Write-Host "[0/7] Checking toolchain..."
$javaExe = (Get-Command java     -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source)
$jpackageExe = (Get-Command jpackage -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source)
if (-not $javaExe) { throw "java not found on PATH. Install JDK 17+." }
if (-not $jpackageExe) { throw "jpackage not found. Install JDK 14+." }
Write-Host "      java:     $javaExe"
Write-Host "      jpackage: $jpackageExe"

# 1. H2 database
Write-Host "[1/7] Checking H2 database..."
if (-not (Test-Path "$LIB\h2.jar")) {
    Write-Host "      Downloading H2..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar" `
        -OutFile "$LIB\h2.jar" -UseBasicParsing
}
Write-Host "      H2 ready"

# 2. JavaFX SDK
Write-Host "[2/7] Checking JavaFX SDK..."
if (-not (Test-Path "$JAVAFX_SDK\lib\javafx.base.jar")) {
    Write-Host "      Downloading JavaFX 21 SDK for Windows..."
    $zip = "$env:TEMP\javafx-sdk.zip"
    Invoke-WebRequest -Uri "https://download2.gluonhq.com/openjfx/21.0.1/openjfx-21.0.1_windows-x64_bin-sdk.zip" `
        -OutFile $zip -UseBasicParsing
    Write-Host "      Extracting..."
    Expand-Archive -Path $zip -DestinationPath $BASE -Force
    $extracted = Get-ChildItem -Path $BASE -Directory -Filter "javafx-sdk-*" | Select-Object -First 1
    if ($extracted) { Rename-Item -Path $extracted.FullName -NewName "javafx-sdk" }
}
Write-Host "      JavaFX SDK ready"

# 3. Compile
Write-Host "[3/7] Compiling Java sources..."
if (Test-Path $BUILD_CLS) { Remove-Item -Recurse -Force $BUILD_CLS }
New-Item -ItemType Directory -Path $BUILD_CLS -Force | Out-Null

$javaFiles = Get-ChildItem -Path $SRC -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName
Write-Host "      Found $($javaFiles.Count) source files"

$cpJars = @()
Get-ChildItem -Path $LIB    -Filter "*.jar" | ForEach-Object { $cpJars += $_.FullName }
Get-ChildItem -Path "$JAVAFX_SDK\lib" -Filter "*.jar" | ForEach-Object { $cpJars += $_.FullName }
$cp = $cpJars -join ";"

$javacArgs = @(
    "-encoding", "UTF-8",
    "--module-path", "$JAVAFX_SDK\lib",
    "--add-modules", "java.base,java.desktop,java.logging,java.sql,java.naming,java.management,java.xml,jdk.crypto.ec,java.net.http,jdk.localedata,javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.swing",
    "-cp", $cp,
    "-d", $BUILD_CLS
) + $javaFiles

& javac @javacArgs
if ($LASTEXITCODE -ne 0) { throw "Compilation failed!" }
Write-Host "      Compilation successful!"

# Copy resources (FXML, CSS, Icons)
Write-Host "      Syncing resources..."
if (Test-Path $RES) {
    Copy-Item -Path "$RES\*" -Destination $BUILD_CLS -Recurse -Force
}

# 4. Create JAR
Write-Host "[4/7] Creating application JAR..."
if (Test-Path $DIST) { Remove-Item -Recurse -Force $DIST }
New-Item -ItemType Directory -Path "$DIST\input" -Force | Out-Null

"Main-Class: com.forge.aiteam.MainApp" | Out-File -FilePath "$BASE\build\MANIFEST.MF" -Encoding ASCII

Push-Location $BUILD_CLS
& jar cfm "$DIST\input\BrainAI.jar" "$BASE\build\MANIFEST.MF" .
Pop-Location

# Copy dependencies
Get-ChildItem -Path $LIB    -Filter "*.jar" | Copy-Item -Destination "$DIST\input"
Get-ChildItem -Path "$JAVAFX_SDK\lib" -Filter "*.jar" | Copy-Item -Destination "$DIST\input"
Get-ChildItem -Path "$JAVAFX_SDK\bin" -Filter "*.dll" -ErrorAction SilentlyContinue | Copy-Item -Destination "$DIST\input"
Write-Host "      JAR ready"

# 5. jlink custom runtime
Write-Host "[5/7] Building custom JRE (jlink)..."
if (Test-Path $RUNTIME) { Remove-Item -Recurse -Force $RUNTIME }

$jexe = (Get-Command java -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source)
if ($jexe) {
    $javaHome = Split-Path -Parent (Split-Path -Parent $jexe)
}
else {
    $javaHome = [System.Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
    if (-not $javaHome) { $javaHome = $env:JAVA_HOME }
}

$jlinkArgs = @(
    "--module-path", "$JAVAFX_SDK\lib;$javaHome\jmods",
    "--add-modules", "java.base,java.desktop,java.logging,java.sql,java.naming,java.management,java.xml,jdk.crypto.ec,java.net.http,jdk.localedata,javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.swing",
    "--output", $RUNTIME,
    "--strip-debug", "--compress=2", "--no-header-files", "--no-man-pages"
)
& jlink @jlinkArgs
$runtimeArg = if ($LASTEXITCODE -eq 0) {
    Write-Host "      Custom runtime built"
    @("--runtime-image", $RUNTIME)
}
else {
    Write-Host "      jlink failed - fallback to system JRE"
    @()
}

# 6. jpackage
Write-Host "[6/7] Packaging installer..."

# Icon path (use .png, jpackage will try to convert or use default if it fails)
$iconPath = "$RES\com\forge\aiteam\icon.png"
$iconArg = if (Test-Path $iconPath) { @("--icon", $iconPath) } else { @() }

$commonArgs = @(
    "--name", "BrainAI",
    "--app-version", "1.0.0",
    "--vendor", "Ayushman Singh",
    "--description", "Brain AI Assistant - AI code generation pipeline",
    "--input", "$DIST\input",
    "--main-jar", "BrainAI.jar",
    "--main-class", "com.forge.aiteam.MainApp",
    "--dest", $DIST,
    "--java-options", "--add-modules=javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.swing,jdk.crypto.ec,java.net.http",
    "--java-options", "-Dfile.encoding=UTF-8"
) + $runtimeArg + $iconArg

# Try .exe (needs WiX)
try {
    & jpackage --type exe --win-menu --win-shortcut --win-dir-chooser --win-per-user-install @commonArgs 2>$null
}
catch {
    # Ignore and fall through to check for output file
}

$exeFile = Get-ChildItem "$DIST\*.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($exeFile) {
    Write-Host "      SUCCESS! Windows Installer: $($exeFile.FullName)"
    Write-Host 'Generated projects saved to: ~/.brainai/projects'
    pause
    exit 0
}

# Fallback: portable app-image + zip
Write-Host "      WiX not found - building portable app folder..."
try {
    & jpackage --type app-image @commonArgs 2>$null
}
catch {
    # Some warnings are emitted as errors by PowerShell, ignore then check existence
}

$appImage = "$DIST\BrainAI"
if (Test-Path $appImage) {
    Write-Host "[7/7] Zipping portable app..."
    $zipPath = "$DIST\BrainAI-Windows-Portable.zip"
    Compress-Archive -Path $appImage -DestinationPath $zipPath -Force
    $sizeMB = [math]::Round((Get-Item $zipPath).Length / 1MB, 1)
    Write-Host "      SUCCESS! Portable App ($sizeMB MB): $zipPath"
}
else {
    Write-Error "Packaging failed. No output produced."
    exit 1
}

Write-Host ""
Write-Host 'Generated projects saved to: ~/.brainai/projects'
pause
