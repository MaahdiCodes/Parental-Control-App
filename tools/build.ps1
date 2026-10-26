<#
.SYNOPSIS
  Runs the core tests and builds both apps (no Android Studio needed).
  Outputs: app-tv\build\outputs\apk\debug\app-tv-debug.apk
           app-parent\build\outputs\apk\debug\app-parent-debug.apk

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\build.ps1
#>

$root = Resolve-Path (Join-Path $PSScriptRoot '..')

if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $jdk = Get-ChildItem 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $jdk) {
        throw 'JDK 17 not found. Install it with: winget install --id Microsoft.OpenJDK.17 -e'
    }
    $env:JAVA_HOME = $jdk.FullName
}

$localProps = Join-Path $root 'local.properties'
if (-not (Test-Path $localProps)) {
    $sdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk') -replace '\\', '/'
    Set-Content -Path $localProps -Value "sdk.dir=$sdk" -Encoding ASCII
}

# The Firebase config downloaded from the console lives in the project root; each app needs a copy.
$firebaseConfig = Join-Path $root 'google-services.json'
if (-not (Test-Path $firebaseConfig)) {
    throw 'google-services.json missing: download it from Firebase console > Project settings and put it in the project folder.'
}
foreach ($module in 'app-tv', 'app-parent') {
    Copy-Item $firebaseConfig (Join-Path $root "$module\google-services.json") -Force
}

Push-Location $root
try {
    & .\gradlew.bat :core:test :app-tv:assembleDebug :app-parent:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Build failed: copy the error above to Claude.' }
    Write-Host ''
    Write-Host 'Built app-tv\build\outputs\apk\debug\app-tv-debug.apk' -ForegroundColor Green
    Write-Host 'Built app-parent\build\outputs\apk\debug\app-parent-debug.apk' -ForegroundColor Green
} finally {
    Pop-Location
}
