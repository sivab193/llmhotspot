$ErrorActionPreference = 'Stop'

$missing = [System.Collections.Generic.List[string]]::new()

if (-not (Get-Command java -ErrorAction SilentlyContinue)) { $missing.Add('JDK 17 (java)') }
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) { $missing.Add('ANDROID_HOME or ANDROID_SDK_ROOT') }
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { $missing.Add('Android platform-tools (adb)') }

if ($missing.Count -gt 0) {
    Write-Error ("Missing Android toolchain components:`n - " + ($missing -join "`n - "))
}

Write-Host 'Android toolchain entry points are present.' -ForegroundColor Green
