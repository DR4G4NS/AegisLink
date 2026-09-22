[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$PackagePath,

    [string]$InstalledExecutable = "$env:ProgramFiles\Aegis Remote Desktop\Aegis Remote Desktop.exe",

    [string]$ExpectedSignerSubject = $env:AEGIS_WINDOWS_CERT_SUBJECT,

    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string]$ExpectedSha256,

    [switch]$AllowRepair,
    [switch]$Install
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Resolve-RegularFile([string]$Path, [string]$Label) {
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    $item = Get-Item -LiteralPath $resolved -Force
    if (-not $item.PSIsContainer -and -not ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        return $item
    }
    throw "PKG-9005: $Label must be a regular file and cannot be a reparse point: $resolved"
}

function Assert-ValidSignature([IO.FileInfo]$File, [string]$Label) {
    $signature = Get-AuthenticodeSignature -LiteralPath $File.FullName
    if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid -or -not $signature.SignerCertificate) {
        throw "PKG-9006: $Label has no valid Authenticode signature (status=$($signature.Status))."
    }
    return $signature
}

function Parse-Version([string]$Value, [string]$Label) {
    $normalized = ($Value -split '[+-]', 2)[0]
    $version = $null
    if (-not [Version]::TryParse($normalized, [ref]$version)) {
        throw "PKG-9007: $Label does not expose a valid numeric version: $Value"
    }
    return $version
}

$package = Resolve-RegularFile $PackagePath 'Update package'
if ($package.Extension -ne '.exe' -or $package.Name -notlike 'Aegis-Remote-Desktop-Setup-*.exe') {
    throw "PKG-9005: the update package is not a canonical Aegis Windows Setup executable."
}

if ($ExpectedSha256) {
    $actualHash = (Get-FileHash -LiteralPath $package.FullName -Algorithm SHA256).Hash
    if (-not $actualHash.Equals($ExpectedSha256, [StringComparison]::OrdinalIgnoreCase)) {
        throw "PKG-9008: update SHA-256 mismatch. expected=$($ExpectedSha256.ToLowerInvariant()) actual=$($actualHash.ToLowerInvariant())"
    }
}

$packageSignature = Assert-ValidSignature $package 'Update package'
$trustedSubject = $ExpectedSignerSubject
$installed = $null
if (Test-Path -LiteralPath $InstalledExecutable -PathType Leaf) {
    $installed = Resolve-RegularFile $InstalledExecutable 'Installed launcher'
    $installedSignature = Assert-ValidSignature $installed 'Installed launcher'
    if ([string]::IsNullOrWhiteSpace($trustedSubject)) {
        $trustedSubject = $installedSignature.SignerCertificate.Subject
    }
    if (-not $installedSignature.SignerCertificate.Subject.Equals($trustedSubject, [StringComparison]::Ordinal)) {
        throw "PKG-9006: installed launcher signer does not match the pinned publisher subject."
    }
}

if ([string]::IsNullOrWhiteSpace($trustedSubject)) {
    throw "PKG-9006: no installed signed launcher or explicit AEGIS_WINDOWS_CERT_SUBJECT is available to pin the publisher."
}
if (-not $packageSignature.SignerCertificate.Subject.Equals($trustedSubject, [StringComparison]::Ordinal)) {
    throw "PKG-9006: update signer '$($packageSignature.SignerCertificate.Subject)' does not match pinned publisher '$trustedSubject'."
}

$packageVersion = Parse-Version $package.VersionInfo.ProductVersion 'Update package'
if ($installed) {
    $installedVersion = Parse-Version $installed.VersionInfo.ProductVersion 'Installed launcher'
    if ($packageVersion -lt $installedVersion -or (($packageVersion -eq $installedVersion) -and -not $AllowRepair)) {
        throw "PKG-9007: update version $packageVersion must be newer than installed version $installedVersion. Use -AllowRepair only for an explicitly approved same-version repair."
    }
}

Write-Host "Verified Aegis Windows update $packageVersion signed by $trustedSubject" -ForegroundColor Green
Write-Output ([pscustomobject]@{
    Path = $package.FullName
    Version = $packageVersion.ToString()
    SignerSubject = $trustedSubject
    Sha256 = (Get-FileHash -LiteralPath $package.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
})

if ($Install) {
    $process = Start-Process -FilePath $package.FullName -ArgumentList '/SP-' -Verb RunAs -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        throw "PKG-9009: verified Setup exited with code $($process.ExitCode)."
    }
}
