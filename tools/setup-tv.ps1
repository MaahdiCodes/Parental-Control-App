<#
.SYNOPSIS
  One-time setup of TV Guardian on a Google TV: installs the app and switches on the
  permissions that Google TV hides from its Settings menus.

.EXAMPLE
  # TV shows "Network debugging" / a plain IP address:
  powershell -ExecutionPolicy Bypass -File tools\setup-tv.ps1 -Address 192.168.1.50

.EXAMPLE
  # TV shows "Wireless debugging" with a pairing code (Android TV 11+):
  powershell -ExecutionPolicy Bypass -File tools\setup-tv.ps1 -Address 192.168.1.50:41234 -PairAddress 192.168.1.50:37099 -PairCode 123456
#>
param(
    [Parameter(Mandatory = $true)][string]$Address,
    [string]$PairAddress,
    [string]$PairCode,
    [string]$Apk
)

. (Join-Path $PSScriptRoot '_adb.ps1')

# Resolved here, not as a param default: Windows PowerShell 5.1 hasn't set $PSScriptRoot yet at that point.
if (-not $Apk) { $Apk = Join-Path $PSScriptRoot '..\app-tv\build\outputs\apk\debug\app-tv-debug.apk' }

$Address = Resolve-TvAddress $Address

if ($PairAddress) {
    if (-not $PairCode) { throw '-PairCode is required together with -PairAddress.' }
    Write-Host "Pairing with $PairAddress ..."
    Write-Host (Invoke-Adb pair $PairAddress $PairCode)
}

Connect-Tv $Address

if (-not (Test-Path $Apk)) {
    throw "APK not found at $Apk. Build it first: powershell -ExecutionPolicy Bypass -File tools\build.ps1"
}
Write-Host 'Installing TV Guardian ...'
Write-Host (Invoke-Adb -s $Address install -r $Apk)

Write-Host 'Granting notification access (lets the app see what is playing) ...'
Invoke-Adb -s $Address shell cmd notification allow_listener "$Package/$Package.MediaWatcherService" | Out-Null

Write-Host 'Enabling the accessibility service (foreground app, lock screen, HOME/BACK) ...'
$service = "$Package/$Package.GuardAccessibilityService"
$existing = Invoke-Adb -s $Address shell settings get secure enabled_accessibility_services
if (-not $existing -or $existing -eq 'null') {
    $enabled = $service
} elseif ($existing -like "*$service*") {
    $enabled = $existing
} else {
    $enabled = "${existing}:${service}"
}
Invoke-Adb -s $Address shell settings put secure enabled_accessibility_services $enabled | Out-Null
Invoke-Adb -s $Address shell settings put secure accessibility_enabled 1 | Out-Null

Write-Host 'Opening TV Guardian on the TV ...'
Invoke-Adb -s $Address shell am start -n "$Package/.MainActivity" | Out-Null

Write-Host ''
Write-Host 'Done. On the TV, both "Accessibility" and "Notification access" should show ON.' -ForegroundColor Green
Write-Host 'Next: press "Run automatic test" on the TV (docs/SETUP.md, step 6).'
