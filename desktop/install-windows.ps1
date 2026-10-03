# Install Ramble on Windows: the `ramble` command, optional local tidy-up with
# Ollama, and a Startup shortcut so it's always running. Safe to run again to update.
#
#   powershell -ExecutionPolicy Bypass -File install-windows.ps1
#
# Experimental: Windows support is new. Please report what works and what doesn't.
$ErrorActionPreference = "Stop"
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Model = "qwen2.5:1.5b"

function Step($text) { Write-Host "`n> $text" -ForegroundColor Cyan }

Step "uv (Python tooling)"
if (-not (Get-Command uv -ErrorAction SilentlyContinue)) {
    powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"
    $env:Path = "$env:USERPROFILE\.local\bin;$env:Path"
}
uv --version

Step "Ramble"
uv tool install --force --python 3.12 "$Here"
$Bin = (uv tool dir --bin).Trim()
$Ramble = Join-Path $Bin "ramble.exe"
if (-not (Test-Path "$env:APPDATA\Ramble\config.toml")) { & $Ramble init }

Step "Ollama + $Model for tidy-up (optional)"
if (Get-Command ollama -ErrorAction SilentlyContinue) {
    ollama pull $Model
} else {
    Write-Host "Ollama isn't installed. Ramble works without it (the built-in rules tidy up your text)."
    Write-Host "For smarter tidy-up: winget install Ollama.Ollama, then run this script again."
}

Step "Downloading the speech model and checking the microphone"
Write-Host "If Windows asks about microphone access, allow it."
& $Ramble check

Step "Start at login"
# pythonw runs Ramble without a console window; its output goes to %LOCALAPPDATA%\Ramble\ramble.log.
$ToolDir = (uv tool dir).Trim()
$Pythonw = Join-Path $ToolDir "ramble\Scripts\pythonw.exe"
$Startup = [Environment]::GetFolderPath("Startup")
$Shell = New-Object -ComObject WScript.Shell
$Link = $Shell.CreateShortcut((Join-Path $Startup "Ramble.lnk"))
$Link.TargetPath = $Pythonw
$Link.Arguments = "-m ramble"
$Link.WorkingDirectory = $env:USERPROFILE
$Link.Description = "Ramble dictation"
$Link.Save()

# Restart it now with the new version.
Get-CimInstance Win32_Process -Filter "Name = 'pythonw.exe'" |
    Where-Object { $_.CommandLine -like "*-m ramble*" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Start-Process $Pythonw -ArgumentList "-m", "ramble" -WindowStyle Hidden

Write-Host @"

Ramble is running. Hold Right Ctrl to talk and let go to paste; tap it for hands-free.
Settings: $env:APPDATA\Ramble\config.toml   Log: $env:LOCALAPPDATA\Ramble\ramble.log
To stop it starting at login, delete the Ramble shortcut in: $Startup
"@
