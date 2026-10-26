<#
.SYNOPSIS
  Copies the Phase 0 test log from the TV to spike-log.txt in the project folder.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\pull-log.ps1 -Address 192.168.1.50
#>
param(
    [Parameter(Mandatory = $true)][string]$Address
)

. (Join-Path $PSScriptRoot '_adb.ps1')

$Address = Resolve-TvAddress $Address
Connect-Tv $Address

$log = Invoke-Adb -s $Address exec-out run-as $Package cat files/spike-log.txt
$target = Join-Path $PSScriptRoot '..\spike-log.txt'
Set-Content -Path $target -Value $log -Encoding UTF8
Write-Host $log
Write-Host ''
Write-Host "Saved to $((Resolve-Path $target).Path)" -ForegroundColor Green
