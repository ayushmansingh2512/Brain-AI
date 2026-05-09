<#
.SYNOPSIS
    Brain AI Pro Developer Tools - A Swiss Army Knife for the Brain AI platform.
    Created for Ayushman Singh.

.DESCRIPTION
    This script provides a CLI interface to manage the Brain AI environment:
    - View/Export project history from the H2 database.
    - Reset or repair the embedded database.
    - Launch the H2 Web Console for manual SQL.
    - Validate Gemini API keys from keys.txt or config.properties.
    - Force-update system prompts in the database.

.USAGE
    .\brainai-manager.ps1
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ── Paths & Configuration ──────────────────────────────────────────────────
$BaseDir    = $PSScriptRoot
$ConfigDir  = Join-Path $env:USERPROFILE ".brainai"
$DbFile     = Join-Path $ConfigDir "brainai_db.mv.db"
$H2Jar      = Join-Path $BaseDir "lib\h2.jar"
$ConfigProp = Join-Path $ConfigDir "config.properties"
$KeysFile   = Join-Path $BaseDir "keys.txt"

# ── Header ──────────────────────────────────────────────────────────────────
function Write-Header {
    Clear-Host
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host "             🧠 BRAIN AI - DEVELOPER TOOLS" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host "  DB Path: $DbFile" -ForegroundColor Gray
    Write-Host ""
}

# ── Database Functions ──────────────────────────────────────────────────────
function Run-SQL {
    param([string]$sql, [bool]$showResult = $true)
    
    if (-not (Test-Path $H2Jar)) {
        Write-Error "h2.jar not found in lib folder. Please run build-installer.ps1 first."
        return
    }

    $jdbcUrl = "jdbc:h2:$ConfigDir\brainai_db;AUTO_SERVER=TRUE"
    
    # Use H2 RunScript/Shell tool via Java
    $tmp = [System.IO.Path]::GetTempFileName()
    $sqlCommand = "$sql;"
    $sqlCommand | Out-File -FilePath "$tmp.sql" -Encoding ascii
    
    $result = java -cp $H2Jar org.h2.tools.Shell -url $jdbcUrl -user sa -password "" -sql (Get-Content "$tmp.sql" -Raw)
    
    Remove-Item "$tmp" -ErrorAction SilentlyContinue
    Remove-Item "$tmp.sql" -ErrorAction SilentlyContinue
    
    if ($showResult) { return $result }
}

function List-Projects {
    Write-Header
    Write-Host "📋 PROJECT HISTORY" -ForegroundColor Yellow
    Write-Host "------------------------------------------------------------"
    $results = Run-SQL "SELECT project_id, name, status FROM projects ORDER BY project_id DESC"
    if ($results) {
        $results | ForEach-Object { Write-Host $_ }
    } else {
        Write-Host "No projects found." -ForegroundColor Gray
    }
    Write-Host ""
    Read-Host "Press Enter to return to menu..."
}

function View-Project-Messages {
    $id = Read-Host "Enter Project ID to view"
    if (-not $id) { return }
    
    Write-Header
    Write-Host "💬 MESSAGES FOR PROJECT #$id" -ForegroundColor Yellow
    Write-Host "------------------------------------------------------------"
    $results = Run-SQL "SELECT sender_role, SUBSTRING(message_content, 1, 100) AS snippet FROM messages WHERE project_id = $id"
    if ($results) {
        $results | ForEach-Object { Write-Host $_ }
    } else {
        Write-Host "No messages found for ID $id" -ForegroundColor Red
    }
    Write-Host ""
    
    $choice = Read-Host "Enter 'all' to export full logs to file, or press Enter to return"
    if ($choice -eq 'all') {
        $outFile = Join-Path $BaseDir "Project_$id`_Logs.txt"
        $full = Run-SQL "SELECT sender_role, message_content FROM messages WHERE project_id = $id"
        $full | Out-File $outFile
        Write-Host "✅ Exported to: $outFile" -ForegroundColor Green
        Start-Sleep -Seconds 2
    }
}

function Launch-H2-Console {
    Write-Host "🚀 Launching H2 Web Console..." -ForegroundColor Cyan
    Write-Host "   URL: jdbc:h2:$ConfigDir\brainai_db" -ForegroundColor Gray
    Write-Host "   User: sa | Password: [empty]" -ForegroundColor Gray
    Write-Host "   Press Ctrl+C in this terminal to stop the console server."
    
    # Run in background then browse
    Start-Process "http://localhost:8082"
    java -cp $H2Jar org.h2.tools.Console -url "jdbc:h2:$ConfigDir\brainai_db" -user sa -password ""
}

function Reset-Database {
    $confirm = Read-Host "⚠️  Are you sure you want to DELETE the database? (y/N)"
    if ($confirm -eq 'y') {
        if (Test-Path $DbFile) {
            try {
                Remove-Item (Join-Path $ConfigDir "brainai_db.*") -Force
                Write-Host "✅ Database wiped. It will be recreated on next app launch." -ForegroundColor Green
            } catch {
                Write-Host "❌ FAILED: Database file is locked. Close Brain AI first." -ForegroundColor Red
            }
        } else {
            Write-Host "ℹ️  Database file not found."
        }
    }
    Start-Sleep -Seconds 2
}

# ── API Validation ─────────────────────────────────────────────────────────
function Test-Gemini-Keys {
    Write-Header
    Write-Host "🔑 VALIDATING API KEYS" -ForegroundColor Yellow
    
    $keys = @()
    if (Test-Path $KeysFile) {
        $keys += Get-Content $KeysFile | Where-Object { $_ -match "^AIza" }
    }
    if (Test-Path $ConfigProp) {
        # Simple manual parsing for .properties format
        Get-Content $ConfigProp | ForEach-Object {
            if ($_ -match "GOOGLE_API_KEY\s*=\s*(.+)") {
                $raw = $matches[1]
                $raw.Split(",") | ForEach-Object { $keys += $_.Trim() }
            }
        }
    }
    
    $uniqueKeys = $keys | Select-Object -Unique | Where-Object { $_ -ne $null -and $_ -ne "" }
    if ($uniqueKeys.Count -eq 0) {
        Write-Host "❌ No API keys found in keys.txt or config.properties" -ForegroundColor Red
        Read-Host "Press Enter..."
        return
    }
    
    Write-Host "Found $($uniqueKeys.Count) keys. Testing connection..."
    
    foreach ($key in $uniqueKeys) {
        $displayKey = if ($key.Length -gt 12) { $key.Substring(0, 8) + "..." } else { $key }
        Write-Host "Testing $displayKey : " -NoNewline
        
        try {
            $uri = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$key"
            $body = @{ contents = @(@{ parts = @(@{ text = "ping" }) }) } | ConvertTo-Json
            $resp = Invoke-WebRequest -Method Post -Uri $uri -ContentType "application/json" -Body $body -UseBasicParsing -ErrorAction Stop
            Write-Host "✅ VALID" -ForegroundColor Green
        } catch {
            Write-Host "❌ INVALID ($($_.Exception.Message))" -ForegroundColor Red
        }
    }
    Write-Host ""
    Read-Host "Press Enter to return..."
}

# ── Main Loop ───────────────────────────────────────────────────────────────
while ($true) {
    Write-Header
    Write-Host "1. 📁 List All Projects"
    Write-Host "2. 💬 View Project Messages/Logs"
    Write-Host "3. 🌐 Launch H2 Web Console (Manual SQL)"
    Write-Host "4. 🔑 Test Gemini API Keys"
    Write-Host "5. 🔄 Reset Agent Prompts (Wipe table)"
    Write-Host "6. ⚠️  Reset/Wipe Entire Database"
    Write-Host "7. 🚀 Launch Brain AI"
    Write-Host "8. 🧪 Run Quota/Network Tester"
    Write-Host "0. ❌ Exit"
    Write-Host ""
    
    $choice = Read-Host "Choose an option"
    
    switch ($choice) {
        "1" { List-Projects }
        "2" { View-Project-Messages }
        "3" { Launch-H2-Console }
        "4" { Test-Gemini-Keys }
        "5" { 
            $confirm = Read-Host "This will wipe the AGENTS table so the app re-seeds them. Proceed? (y/N)"
            if ($confirm -eq 'y') { Run-SQL "DELETE FROM agents" -showResult $false; Write-Host "✅ Agents wiped." -ForegroundColor Green; Start-Sleep 1 }
        }
        "6" { Reset-Database }
        "7" { 
            if (Test-Path "BrainAI-Direct-Launch.bat") {
                Write-Host "🚀 Launching Brain AI..." -ForegroundColor Cyan
                Start-Process "BrainAI-Direct-Launch.bat"
            } else {
                Write-Host "❌ BrainAI-Direct-Launch.bat not found." -ForegroundColor Red; Start-Sleep 2
            }
        }
        "8" {
            Write-Host "🧪 Compiling and Running QuotaTester..." -ForegroundColor Cyan
            if (Test-Path "QuotaTester.java") {
                $cp = (Get-ChildItem -Path $LibDir -Filter "*.jar" | Select-Object -ExpandProperty FullName) -join ";"
                & javac -cp $cp QuotaTester.java
                & java -cp ".;$cp" QuotaTester
            } else {
                Write-Host "❌ QuotaTester.java not found." -ForegroundColor Red
            }
            Read-Host "Press Enter..."
        }
        "0" { exit }
        default { Write-Host "Invalid choice" -ForegroundColor Red; Start-Sleep -Seconds 1 }
    }
}
