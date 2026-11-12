# Quick Local APK Builder for Windows (No Android Studio required)
# Run this script in PowerShell to build the APK locally.

param(
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot
Set-Location $ProjectRoot

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host " SDCloudHub - Standalone APK Builder" -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

# 1. Check for Java / JDK 17+
$JavaCmd = Get-Command "java" -ErrorAction SilentlyContinue
if (-not $JavaCmd -and -not $env:JAVA_HOME) {
    Write-Host "[!] Java / JDK 17+ is required to compile Android APKs." -ForegroundColor Yellow
    Write-Host "[*] You can install it quickly using winget:" -ForegroundColor Green
    Write-Host "    winget install Microsoft.OpenJDK.17" -ForegroundColor White
    Write-Host "    or download Temurin JDK: https://adoptium.net/temurin/releases/?version=17" -ForegroundColor White
    exit 1
}

# 2. Check for Android SDK
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    $DefaultSdk = "$env:LOCALAPPDATA\Android\Sdk"
    if (Test-Path $DefaultSdk) {
        $env:ANDROID_HOME = $DefaultSdk
        Write-Host "[+] Found Android SDK at: $DefaultSdk" -ForegroundColor Green
    } else {
        Write-Host "[!] ANDROID_HOME environment variable is not set." -ForegroundColor Yellow
        Write-Host "[*] Tip: If you prefer not to install the Android SDK locally," -ForegroundColor Green
        Write-Host "    you can use GitHub Actions to build the APK in the cloud for free with 0 setup!" -ForegroundColor White
        Write-Host "    See instructions in README.md." -ForegroundColor White
        exit 1
    }
}

# 3. Run Gradle Build
Write-Host "[*] Building APK with Gradle..." -ForegroundColor Cyan
if ($Clean) {
    .\gradlew.bat clean assembleDebug
} else {
    .\gradlew.bat assembleDebug
}

$ApkPath = "$ProjectRoot\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $ApkPath) {
    $Destination = "$ProjectRoot\SDCloudHub-debug.apk"
    Copy-Item $ApkPath $Destination -Force
    Write-Host "=================================================" -ForegroundColor Green
    Write-Host " [SUCCESS] APK compiled successfully!" -ForegroundColor Green
    Write-Host " Location: $Destination" -ForegroundColor Yellow
    Write-Host "=================================================" -ForegroundColor Green
} else {
    Write-Host "[-] Build finished but APK was not found at $ApkPath" -ForegroundColor Red
}
