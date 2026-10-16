# ClipForge setup-and-run script.
# Checks every prerequisite ClipForge needs, installs whatever is missing
# (via winget / pip), then launches the app. Safe to re-run any time -
# anything already installed is left alone.

$ErrorActionPreference = "Continue"

function Test-Command($name) {
    return [bool](Get-Command $name -ErrorAction SilentlyContinue)
}

Write-Host "=== ClipForge environment setup ===" -ForegroundColor Cyan

if (-not (Test-Command winget)) {
    Write-Host "winget is not available on this machine. Install the missing tools below manually, then re-run this script:" -ForegroundColor Red
    Write-Host " - Java 17+ (https://adoptium.net)"
    Write-Host " - ffmpeg + ffprobe on PATH (https://www.gyan.dev/ffmpeg/builds/)"
    Write-Host " - yt-dlp on PATH (https://github.com/yt-dlp/yt-dlp)"
    Write-Host " - Python 3.10+ on PATH (https://python.org)"
    exit 1
}

$needsRestart = $false

# ---- 1. Java 17+ ----
Write-Host "`n[1/5] Java 17+..." -ForegroundColor Yellow
$javaOk = $false
if (Test-Command java) {
    $verOutput = (& java -version 2>&1) -join "`n"
    $major = 0
    if ($verOutput -match 'version "(\d+)') { $major = [int]$Matches[1] }
    if ($major -ge 17) { $javaOk = $true; Write-Host "  Java $major found." -ForegroundColor Green }
    else { Write-Host "  Java $major found, but 17+ is required." -ForegroundColor Yellow }
}
if (-not $javaOk) {
    Write-Host "  Installing Eclipse Temurin 17 JDK..." -ForegroundColor Yellow
    winget install --id EclipseAdoptium.Temurin.17.JDK -e --accept-package-agreements --accept-source-agreements
    $needsRestart = $true
}

# ---- 2. ffmpeg / ffprobe ----
Write-Host "`n[2/5] ffmpeg / ffprobe..." -ForegroundColor Yellow
if ((Test-Command ffmpeg) -and (Test-Command ffprobe)) {
    Write-Host "  Found." -ForegroundColor Green
} else {
    Write-Host "  Installing ffmpeg..." -ForegroundColor Yellow
    winget install --id Gyan.FFmpeg -e --accept-package-agreements --accept-source-agreements
    $needsRestart = $true
}

# ---- 3. yt-dlp ----
Write-Host "`n[3/5] yt-dlp..." -ForegroundColor Yellow
if (Test-Command yt-dlp) {
    Write-Host "  Found." -ForegroundColor Green
} else {
    Write-Host "  Installing yt-dlp..." -ForegroundColor Yellow
    winget install --id yt-dlp.yt-dlp -e --accept-package-agreements --accept-source-agreements
    $needsRestart = $true
}

# ---- 4. Python + AI packages (torch, Pillow, propainter, whisper) ----
# Several Pythons are often installed side by side, and the one first on PATH is not
# necessarily the one with packages installed (this bit ClipForge itself during dev:
# 3.13 was first on PATH, but only 3.10 had torch/propainter/whisper). So every
# installed Python is tested, and whichever one already has everything is used -
# matching the detection logic ClipForge's own app uses at runtime.
Write-Host "`n[4/5] Python + AI packages..." -ForegroundColor Yellow

function Get-PythonCandidates {
    $cands = @()
    try {
        $whereOut = & where.exe python 2>$null
        foreach ($line in $whereOut) {
            if ($line -and (Test-Path $line) -and ($line -notmatch 'WindowsApps')) { $cands += $line }
        }
    } catch {}
    if (Test-Command py) { $cands += "py" }
    # @(...) forces an array even when there's exactly one (or zero) results -- without it,
    # PowerShell collapses a single-item pipeline result to a plain string, and indexing
    # that with [0] silently grabs its first CHARACTER instead of the first array element.
    return @($cands | Select-Object -Unique)
}

# Written to a temp .py file and run as a script, not passed via "-c" -- a multi-line
# string with embedded quotes gets mangled when PowerShell marshals it as an argument
# to a native exe, which silently broke this check (errors were being swallowed and
# every candidate looked like it failed, even ones with everything installed).
$checkScriptPath = Join-Path $env:TEMP "clipforge_pycheck.py"
@'
import importlib.util as u, sys
missing = [m for m in ("torch", "PIL", "propainter") if not u.find_spec(m)]
if not (u.find_spec("faster_whisper") or u.find_spec("whisper")):
    missing.append("whisper")
sys.exit(1 if missing else 0)
'@ | Set-Content -Path $checkScriptPath -Encoding ASCII

$python = $null
# @() wraps the CALL too -- PowerShell re-collapses a single-item array back to a plain
# string at the assignment itself, even though the function already returns an array.
$candidates = @(Get-PythonCandidates)
foreach ($c in $candidates) {
    & $c $checkScriptPath
    if ($LASTEXITCODE -eq 0) { $python = $c; break }
}
Remove-Item $checkScriptPath -ErrorAction SilentlyContinue

if ($python) {
    Write-Host "  All required Python packages already found on: $python" -ForegroundColor Green
} else {
    if ($candidates.Count -gt 0) {
        $python = $candidates[0]
        Write-Host "  Required packages missing on every installed Python. Installing into: $python" -ForegroundColor Yellow
    } else {
        Write-Host "  Python not found. Installing Python 3.10..." -ForegroundColor Yellow
        winget install --id Python.Python.3.10 -e --accept-package-agreements --accept-source-agreements
        Write-Host "  Python just installed. FULLY CLOSE AND REOPEN your terminal app (if you're in VS Code, close VS Code entirely, not just the terminal tab -- it only reads PATH once, when it starts) then re-run this script." -ForegroundColor Red
        exit 0
    }
    if (Test-Command nvidia-smi) {
        Write-Host "  NVIDIA GPU detected - installing CUDA-enabled PyTorch." -ForegroundColor Yellow
        & $python -m pip install torch torchvision --index-url https://download.pytorch.org/whl/cu124
    } else {
        Write-Host "  No NVIDIA GPU detected - installing CPU-only PyTorch (still works, just slower)." -ForegroundColor Yellow
        & $python -m pip install torch torchvision
    }
    & $python -m pip install pillow propainter faster-whisper
}

if ($needsRestart) {
    Write-Host "`nSome tools were just installed. FULLY CLOSE AND REOPEN your terminal app (if you're in VS Code, close VS Code entirely, not just the terminal tab -- it only reads PATH once, when it starts), then re-run this script." -ForegroundColor Red
    exit 0
}

# ---- 5. Run ClipForge ----
Write-Host "`n[5/5] Everything is in place - starting ClipForge..." -ForegroundColor Cyan
& .\gradlew.bat run
