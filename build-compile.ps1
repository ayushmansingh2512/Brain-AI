# build-compile.ps1 — called by build-windows.bat (no arguments needed)
# Self-contained: discovers all paths relative to its own location.
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$BaseDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$LibDir    = Join-Path $BaseDir "lib"
$SrcDir    = Join-Path $BaseDir "src\main\java"
$ResDir    = Join-Path $BaseDir "src\main\resources"
$BuildDir  = Join-Path $BaseDir "build\classes"
$JavaFxSdk = Join-Path $BaseDir "javafx-sdk"

# ── Collect all .java files ────────────────────────────────────────────────
$javaFiles = Get-ChildItem -Path $SrcDir -Filter "*.java" -Recurse |
             Select-Object -ExpandProperty FullName

if ($javaFiles.Count -eq 0) {
    Write-Error "No .java files found under: $SrcDir"
    exit 1
}
Write-Host "      Found $($javaFiles.Count) Java source files."

# ── Build classpath ────────────────────────────────────────────────────────
$cpJars = @()
Get-ChildItem -Path $LibDir    -Filter "*.jar" | ForEach-Object { $cpJars += $_.FullName }
Get-ChildItem -Path "$JavaFxSdk\lib" -Filter "*.jar" | ForEach-Object { $cpJars += $_.FullName }
$classpath = $cpJars -join ";"

# ── Ensure build/classes exists ───────────────────────────────────────────
New-Item -ItemType Directory -Path $BuildDir -Force | Out-Null

# ── Run javac ──────────────────────────────────────────────────────────────
Write-Host "      Running javac on $($javaFiles.Count) files..."
$javacArgs = @(
    "-encoding", "UTF-8",
    "--module-path", "$JavaFxSdk\lib",
    "--add-modules", "javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.swing",
    "-cp", $classpath,
    "-d", $BuildDir
) + $javaFiles

& javac @javacArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "javac failed with exit code $LASTEXITCODE"
    exit 1
}

# ── Copy resources (FXML, CSS, etc.) ──────────────────────────────────────
Write-Host "      Copying resources..."
Get-ChildItem -Path $ResDir -Recurse | ForEach-Object {
    $dest = $_.FullName.Replace($ResDir, $BuildDir)
    if ($_.PSIsContainer) {
        New-Item -ItemType Directory -Path $dest -Force | Out-Null
    } else {
        Copy-Item -Path $_.FullName -Destination $dest -Force
    }
}

Write-Host "      Compile step complete."
exit 0
