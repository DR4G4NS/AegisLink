[CmdletBinding()]
param(
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version = "0.2.0",
    [switch]$RunTests
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$root = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$distRoot = Join-Path $root "dist"
$dist = Join-Path $distRoot "Aegis-Remote-$Version"
$staging = Join-Path $distRoot ".Aegis-Remote-$Version-staging-$PID"
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$step = 0
$totalSteps = if ($RunTests) { 8 } else { 7 }

function Write-Banner {
    Write-Host ""
    Write-Host "  +----------------------------------------------------------+" -ForegroundColor DarkGreen
    Write-Host "  |              AEGIS DEVELOPER BUNDLE                     |" -ForegroundColor Green
    Write-Host "  |        Android + Windows / version $Version                |" -ForegroundColor DarkGreen
    Write-Host "  +----------------------------------------------------------+" -ForegroundColor DarkGreen
    Write-Host ""
}

function Write-Step([string]$Title, [string]$Detail) {
    $script:step += 1
    Write-Host ("[{0}/{1}] " -f $script:step, $script:totalSteps) -NoNewline -ForegroundColor DarkGray
    Write-Host $Title -ForegroundColor Cyan
    if ($Detail) {
        Write-Host "      $Detail" -ForegroundColor DarkGray
    }
}

function Invoke-Checked([string]$Label, [scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed with exit code $LASTEXITCODE."
    }
}

function Assert-UnderRoot([string]$Path) {
    $resolved = [System.IO.Path]::GetFullPath($Path)
    $prefix = $root.TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify a path outside the repository: $resolved"
    }
    return $resolved
}

function Remove-SafeTree([string]$Path) {
    $safePath = Assert-UnderRoot $Path
    for ($attempt = 1; $attempt -le 8; $attempt += 1) {
        if (-not (Test-Path -LiteralPath $safePath)) {
            return
        }
        try {
            Remove-Item -LiteralPath $safePath -Recurse -Force -ErrorAction Stop
            return
        }
        catch [System.IO.IOException] {
            if ($attempt -eq 8) {
                throw
            }
            # jpackage and real-time scanners can retain runtime files briefly
            # after they exit. Give those handles time to close before retrying.
            Start-Sleep -Milliseconds (250 * $attempt)
        }
    }
}

Write-Banner
Push-Location $root
try {
    Write-Step "Preparing clean output" "Old Gradle package outputs are removed; the previous bundle remains available until success."
    Remove-SafeTree $staging
    Remove-SafeTree (Join-Path $root "app-android\build\outputs\apk\debug")
    Remove-SafeTree (Join-Path $root "app-desktop\desktop-main\build\compose\binaries\main")
    New-Item -ItemType Directory -Path $staging -Force | Out-Null

    if ($RunTests) {
        Write-Step "Running verification suite" "Use without -RunTests for a faster packaging-only pass."
        Invoke-Checked "Gradle tests" {
            & .\gradlew.bat `
                "-PaegisVersion=$Version" `
                :app-desktop:desktop-agent:test `
                :app-desktop:desktop-input:test `
                :app-desktop:desktop-clipboard:test `
                :app-desktop:desktop-webrtc:test `
                :app-desktop:desktop-main:test `
                :shared:core-pairing:jvmTest `
                :app-android:testDebugUnitTest `
                --console=plain
        }
    }

    Write-Step "Building Android APK" "Compiling the installable debug-signed Android artifact."
    Invoke-Checked "Android build" {
        & .\gradlew.bat "-PaegisVersion=$Version" :app-android:assembleDebug --console=plain
    }

    Write-Step "Building Windows application" "Creating the bundled desktop runtime and application image."
    Invoke-Checked "Windows distributable" {
        & .\gradlew.bat "-PaegisVersion=$Version" :app-desktop:desktop-main:createDistributable --console=plain
    }

    Write-Step "Generating SBOM" "Producing CycloneDX JSON/XML evidence for the complete dependency graph."
    Invoke-Checked "CycloneDX SBOM" {
        & .\gradlew.bat "-PaegisVersion=$Version" cyclonedxBom --console=plain
    }

    $apk = Join-Path $root "app-android\build\outputs\apk\debug\app-android-debug.apk"
    $windowsLauncher = Join-Path $root "app-desktop\desktop-main\build\compose\binaries\main\app\Aegis Remote Desktop\Aegis Remote Desktop.exe"
    foreach ($required in @($apk, $windowsLauncher)) {
        if (-not $required -or -not (Test-Path -LiteralPath $required)) {
            throw "A required release artifact was not produced: $required"
        }
    }

    Write-Step "Creating canonical Windows Setup" "Embedding OpenSSH provisioning, private-network firewall rules, shortcuts, and uninstall integration."
    $openSshExtracted = & (Join-Path $root 'tools/release/prepare-openssh.ps1')
    $innoCandidates = @(
        (Get-Command ISCC.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -First 1),
        (Join-Path ${env:ProgramFiles(x86)} "Inno Setup 6\ISCC.exe"),
        (Join-Path $env:LOCALAPPDATA "Programs\Inno Setup 6\ISCC.exe")
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
    $iscc = $innoCandidates | Select-Object -First 1
    if (-not $iscc) {
        throw "Inno Setup 6 is required. Install it with: winget install JRSoftware.InnoSetup"
    }
    Invoke-Checked "Windows Setup" {
        & $iscc `
            "/DAppVersion=$Version" `
            "/DOpenSshSourceDir=$(Join-Path $root 'packaging\windows\openssh')" `
            "/DBundledOpenSshDir=$openSshExtracted" `
            "/O$staging" `
            (Join-Path $root "packaging\windows\AegisRemoteDesktop.iss")
    }

    Write-Step "Assembling developer bundle" "Copying named artifacts, the verified-update helper, SBOM, and calculating SHA-256 checksums."
    Copy-Item -LiteralPath $apk -Destination (Join-Path $staging "Aegis-Remote-Android-$Version.apk") -Force
    Copy-Item -LiteralPath (Join-Path $root "tools\release\verify-windows-update.ps1") -Destination $staging -Force
    foreach ($sbom in @("bom.json", "bom.xml")) {
        $source = Join-Path $root "build\reports\cyclonedx\$sbom"
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "The CycloneDX SBOM is incomplete: $source"
        }
        Copy-Item -LiteralPath $source -Destination (Join-Path $staging "aegis-sbom-$Version.$($sbom.Split('.')[-1])") -Force
    }

    $installers = Get-ChildItem $staging -File | Where-Object { $_.Extension -in ".apk", ".exe" }
    if ($installers.Count -lt 2) {
        throw "The developer bundle is incomplete: expected the Android APK and provisioned Windows Setup EXE."
    }
    $artifacts = Get-ChildItem $staging -File
    $checksumLines = $artifacts | Sort-Object Name | ForEach-Object {
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
        "$hash  $($_.Name)"
    }
    [System.IO.File]::WriteAllLines((Join-Path $staging "SHA256SUMS.txt"), $checksumLines)

    Write-Step "Publishing atomically" "Replacing the previous version only after every artifact succeeded."
    Remove-SafeTree $dist
    Move-Item -LiteralPath $staging -Destination $dist

    $stopwatch.Stop()
    Write-Host ""
    Write-Host "  DEVELOPER BUNDLE READY" -ForegroundColor Green
    Write-Host "  $dist" -ForegroundColor White
    Write-Host ("  Completed in {0:mm\:ss}" -f $stopwatch.Elapsed) -ForegroundColor DarkGray
    Write-Host ""
    Get-ChildItem $dist -File | Sort-Object Name | ForEach-Object {
        $sizeMb = [math]::Round($_.Length / 1MB, 1)
        Write-Host ("  {0,-48} {1,7} MB" -f $_.Name, $sizeMb) -ForegroundColor Gray
    }
}
catch {
    $stopwatch.Stop()
    Remove-SafeTree $staging
    Write-Host ""
    Write-Host "  BUNDLE FAILED" -ForegroundColor Red
    Write-Host "  $($_.Exception.Message)" -ForegroundColor Yellow
    Write-Host "  The previous bundle folder was left untouched." -ForegroundColor DarkGray
    throw
}
finally {
    Pop-Location
}
