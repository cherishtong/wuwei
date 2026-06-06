# package-cloud.ps1 — Build + package wuwei for cloud deployment (Windows dev machine)
# Usage: .\scripts\package-cloud.ps1
# Output: deploy\wuwei-cloud-{version}.zip

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

$version = (Get-Content "$root\wuwei-core\build.gradle" | Select-String "version = '([^']+)'").Matches.Groups[1].Value
$outDir = "$root\deploy\wuwei-cloud-$version"
$zipFile = "$root\deploy\wuwei-cloud-$version.zip"
$gradle = "D:\codesoft\gradle-9.3.1\bin\gradle.bat"

Write-Host "=== Wuwei Cloud Packaging v$version ==="

# 1. Build frontend
Write-Host "[1/4] Building frontend..."
Push-Location "$root\wuwei-renderer"
try {
    npm ci --silent
    npm run build
} finally { Pop-Location }

# 2. Build kernel fat JAR
Write-Host "[2/4] Building kernel fat JAR..."
Push-Location "$root\wuwei-core"
try {
    & $gradle fatJar --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally { Pop-Location }

# 3. Assemble deploy directory
Write-Host "[3/4] Assembling deploy package..."
Remove-Item -Recurse -Force $outDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $outDir | Out-Null
New-Item -ItemType Directory -Force "$outDir\dist" | Out-Null

Copy-Item "$root\wuwei-core\build\libs\wuwei-kernel.jar" $outDir
Copy-Item -Recurse "$root\wuwei-renderer\dist\*" "$outDir\dist"

# Cloud config template — API key from env var or placeholder
@"
{
  "version": "$version",
  "llm": {
    "provider": "deepseek",
    "model": "deepseek-v4-pro"
  },
  "logLevel": "info"
}
"@ | Out-File -Encoding utf8 "$outDir\wuwei.json"

# Start script (Linux)
@'
#!/bin/bash
# wuwei cloud start script
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
java -Xmx512m -jar "$SCRIPT_DIR/wuwei-kernel.jar" \
  --profile cloud \
  --web-root "$SCRIPT_DIR/dist" \
  --config "$SCRIPT_DIR/wuwei.json"
'@ -replace "`r`n", "`n" | Out-File -Encoding utf8 "$outDir\start.sh"

# Start script (Windows)
@"
@echo off
REM wuwei cloud start script
java -Xmx512m -jar "%~dp0wuwei-kernel.jar" --profile cloud --web-root "%~dp0dist" --config "%~dp0wuwei.json"
"@ | Out-File -Encoding utf8 "$outDir\start.bat"

# 4. Package
Write-Host "[4/4] Creating zip..."
Remove-Item $zipFile -ErrorAction SilentlyContinue
Compress-Archive -Path $outDir -DestinationPath $zipFile

Write-Host "Done: $zipFile"
Write-Host "Deploy: unzip on server, run start.sh (API key set via UI in SQLite)"
