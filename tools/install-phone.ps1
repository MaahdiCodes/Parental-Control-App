<#
.SYNOPSIS
  Installs the parent app on an Android phone connected by USB (with USB debugging on).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\install-phone.ps1
#>
param(
    [string]$Apk
)

. (Join-Path $PSScriptRoot '_adb.ps1')

if (-not $Apk) { $Apk = Join-Path $PSScriptRoot '..\app-parent\build\outputs\apk\debug\app-parent-debug.apk' }
if (-not (Test-Path $Apk)) {
    throw "APK not found at $Apk. Build it first: powershell -ExecutionPolicy Bypass -File tools\build.ps1"
}

Start-AdbServer
Write-Host 'Looking for a phone connected by USB ...'
$prompted = $false
for ($i = 0; $i -lt 60; $i++) {
    $state = Get-AdbOutput -d get-state
    if ($state -eq 'device') { break }
    if (-not $prompted) {
        Write-Host 'Unlock the phone. If it asks "Allow USB debugging?", tick "Always allow" and tap Allow.' -ForegroundColor Yellow
        $prompted = $true
    }
    Start-Sleep -Seconds 2
}
if ($state -ne 'device') {
    throw 'No phone found. Check the cable, that USB debugging is ON, and that the phone is unlocked.'
}

Write-Host 'Installing TV Guardian (parent app) ...'
Write-Host (Invoke-Adb -d install -r $Apk)
Invoke-Adb -d shell am start -n com.tvguardian.parent/.MainActivity | Out-Null
Write-Host 'Done: TV Guardian is open on your phone.' -ForegroundColor Green
