@echo off
title Brain AI - Direct Launcher
echo.
echo ============================================================
echo   Brain AI Assistant - Launching via System Java
echo ============================================================
echo.

:: 1. Verify Java/JavaFX
if not exist "javafx-sdk\lib" (
    echo ERROR: javafx-sdk not found in this folder.
    echo Please run build-windows.bat first.
    pause
    exit /b 1
)

:: 2. Launch
echo Starting Brain AI...
java --module-path "javafx-sdk\lib" ^
     --add-modules javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.swing ^
     -cp "build\classes;lib\*" ^
     com.forge.aiteam.MainApp

if errorlevel 1 (
    echo.
    echo ERROR: The application crashed or failed to start.
    echo Check the error messages above.
    pause
)
