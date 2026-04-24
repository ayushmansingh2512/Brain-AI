@echo off
setlocal enabledelayedexpansion
title Brain AI - Windows Builder

echo ============================================================
echo   Brain AI Assistant - Robust Builder
echo ============================================================

set "BASE=%~dp0"
set "LIB=%BASE%lib"
set "SRC=%BASE%src\main\java"
set "BUILD=%BASE%build\classes"
set "DIST=%BASE%dist"
set "JAVAFX_SDK=%BASE%javafx-sdk"
set "RUNTIME=%BASE%build\runtime"

:: 0. Check Tools
jpackage --version >nul 2>&1 || (echo ERROR: jpackage not found. & pause & exit /b 1)

:: 1. Compile
echo [1/4] Compiling...
powershell -NoProfile -ExecutionPolicy Bypass -File "%BASE%build-compile.ps1"
if errorlevel 1 (echo ERROR: Compilation failed. & pause & exit /b 1)

:: 2. Prep DIST
echo [2/4] Collecting JARs...
if exist "%DIST%" rmdir /s /q "%DIST%"
mkdir "%DIST%\input"
(echo Main-Class: com.forge.aiteam.MainApp) > "%BASE%build\MANIFEST.MF"
pushd "%BUILD%"
jar cfm "%DIST%\input\BrainAI.jar" "%BASE%build\MANIFEST.MF" .
popd
copy "%LIB%\*.jar" "%DIST%\input\" >nul
copy "%JAVAFX_SDK%\lib\*.jar" "%DIST%\input\" >nul

:: 3. Packaging (Native App)
echo [3/4] Packaging native app...
jpackage ^
  --type app-image ^
  --name "BrainAI" ^
  --vendor "Ayushman Singh" ^
  --input "%DIST%\input" ^
  --main-jar BrainAI.jar ^
  --main-class com.forge.aiteam.MainApp ^
  --dest "%DIST%" ^
  --java-options "--add-modules=javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.swing" ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --win-menu --win-shortcut --win-dir-chooser

:: 4. Done
if exist "%DIST%\BrainAI" (
    echo [4/4] Zipping...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Compress-Archive -Path '%DIST%\BrainAI' -DestinationPath '%DIST%\BrainAI-Windows-Portable.zip' -Force"
    echo SUCCESS! Run via dist\BrainAI\BrainAI.exe
) else (
    echo ERROR: Build failed.
)
pause
