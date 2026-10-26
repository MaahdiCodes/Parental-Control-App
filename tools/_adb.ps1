# Shared helpers for the tools/*.ps1 scripts. Works in Windows PowerShell 5.1 and PowerShell 7.

$script:Package = 'com.tvguardian.tv'

function Get-AdbPath {
    $sdkAdb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    if (Test-Path $sdkAdb) { return $sdkAdb }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw 'adb not found. See docs/SETUP.md, step 1.'
}

$script:Adb = Get-AdbPath

# Runs adb and returns its combined output as plain text (stderr lines included, without PowerShell's error decoration).
function Get-AdbOutput {
    $lines = & $script:Adb @args 2>&1 | ForEach-Object { "$_" }
    return ($lines -join "`n").Trim()
}

# Like Get-AdbOutput, but throws with the output if adb fails.
# Deliberately a simple function (no param block) so adb flags like -s pass straight through in $args.
function Invoke-Adb {
    $out = Get-AdbOutput @args
    if ($LASTEXITCODE -ne 0) { throw "adb $($args -join ' ') failed:`n$out" }
    return $out
}

# Start the adb server detached, so its background process doesn't hold this script's output pipe open.
function Start-AdbServer {
    Start-Process -FilePath $script:Adb -ArgumentList 'start-server' -WindowStyle Hidden -Wait
}

# "192.168.1.50" -> "192.168.1.50:5555"; "192.168.1.50:41234" stays as is.
function Resolve-TvAddress([string]$Address) {
    if ($Address -match ':\d+$') { return $Address }
    return "${Address}:5555"
}

# Connects over Wi-Fi and waits (up to ~2 minutes) for the "Allow USB debugging?" prompt to be accepted on the TV.
function Connect-Tv([string]$Address) {
    Start-AdbServer
    Write-Host "Connecting to the TV at $Address ..."
    $prompted = $false
    for ($i = 0; $i -lt 60; $i++) {
        if ((Get-AdbOutput -s $Address get-state) -eq 'device') {
            Write-Host 'TV connected and authorised.' -ForegroundColor Green
            return
        }
        $out = Get-AdbOutput connect $Address
        if ($out -match 'failed to authenticate|unauthorized|already connected|connected to') {
            if (-not $prompted -and $out -notmatch '^(already )?connected to') {
                Write-Host 'Look at the TV: it asks "Allow USB debugging?". Tick "Always allow from this computer" and press OK.' -ForegroundColor Yellow
                $prompted = $true
            }
        } else {
            throw "Could not connect to ${Address}: $out`nCheck the TV's IP address and that USB debugging is ON (docs/SETUP.md, step 4)."
        }
        Start-Sleep -Seconds 2
    }
    throw 'The TV did not authorise this PC. Run the script again and accept the prompt on the TV.'
}
