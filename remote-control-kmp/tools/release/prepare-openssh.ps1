[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$cacheRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'build/packaging-dependencies'))
$version = '10.0.0.0p2-Preview'
$expectedSha256 = '23f50f3458c4c5d0b12217c6a5ddfde0137210a30fa870e98b29827f7b43aba5'
$dependencyRoot = Join-Path $cacheRoot "openssh-$version"
$archive = Join-Path $dependencyRoot 'OpenSSH-Win64.zip'
$extractedRoot = [IO.Path]::GetFullPath((Join-Path $dependencyRoot 'OpenSSH-Win64'))

New-Item -ItemType Directory -Path $dependencyRoot -Force | Out-Null
if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
    $downloadUrl = "https://github.com/PowerShell/Win32-OpenSSH/releases/download/$version/OpenSSH-Win64.zip"
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $downloadUrl -OutFile $archive
            break
        } catch {
            Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
            if ($attempt -eq 3) { throw }
            Start-Sleep -Seconds (5 * $attempt)
        }
    }
}

# Recheck cached archives too. Extracted executables alone are never trusted as
# evidence that a dependency cache still contains the pinned release.
$actualSha256 = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualSha256 -ne $expectedSha256) {
    throw 'PKG-9003: OpenSSH archive checksum mismatch; remove the cached archive and retry.'
}

$cachePrefix = $cacheRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $extractedRoot.StartsWith($cachePrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'PKG-9003: refusing to replace an OpenSSH directory outside the dependency cache.'
}
if (Test-Path -LiteralPath $extractedRoot) {
    Remove-Item -LiteralPath $extractedRoot -Recurse -Force
}
Expand-Archive -LiteralPath $archive -DestinationPath $dependencyRoot -Force
foreach ($required in @('sshd.exe', 'ssh-keygen.exe', 'sftp-server.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $extractedRoot $required) -PathType Leaf)) {
        throw "PKG-9003: bundled OpenSSH is missing $required"
    }
}
Write-Output $extractedRoot
