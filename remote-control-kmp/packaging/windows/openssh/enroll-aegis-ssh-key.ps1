[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$PublicKeyFile,

    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9._:-]{1,128}$')]
    [string]$DeviceId
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$managedInstanceHelper = Join-Path $PSScriptRoot 'aegis-openssh-managed-instance.ps1'
if (-not (Test-Path -LiteralPath $managedInstanceHelper -PathType Leaf)) {
    throw 'SSH-7329 MANAGED_HELPER_MISSING: the managed-instance validator was not packaged with key enrollment.'
}
. $managedInstanceHelper

$manifest = Read-AegisOpenSshManifest -RequireBinaries -RequireInstanceFiles -RequireConfiguration
$service = Get-CimInstance Win32_Service -Filter "Name='AegisOpenSSH'" -ErrorAction SilentlyContinue
if (-not $service) {
    throw 'SSH-7319 INSTANCE_NOT_PROVISIONED: the isolated Aegis service does not exist.'
}
if ([string]$service.State -ne 'Running') {
    throw "SSH-7319 SERVICE_NOT_RUNNING: AegisOpenSSH is '$($service.State)'; start or repair the isolated instance before enrolling a key."
}
Assert-AegisManagedServiceOwnership -Service $service -Manifest $manifest
Assert-AegisManagedCaller -Manifest $manifest
$authorizedKeysPath = [string]$manifest.authorizedKeysPath
$sshKeygenPath = [string]$manifest.sshKeygenPath

if (-not (Test-Path -LiteralPath $PublicKeyFile -PathType Leaf)) {
    throw 'SSH-7320 PUBLIC_KEY_FILE_MISSING: the enrollment input file does not exist.'
}
if ((Get-Item -LiteralPath $PublicKeyFile).Length -gt 16384) {
    throw 'SSH-7321 PUBLIC_KEY_TOO_LARGE: an SSH public key may not exceed 16 KiB.'
}

$publicKey = (Get-Content -LiteralPath $PublicKeyFile -Raw).Trim()
if ($publicKey.Contains("`r") -or $publicKey.Contains("`n")) {
    throw 'SSH-7322 PUBLIC_KEY_MULTILINE: exactly one OpenSSH public-key record is required.'
}
$fields = $publicKey -split '\s+', 3
if ($fields.Count -lt 2 -or $fields[0] -notmatch '^(ssh-ed25519|ecdsa-sha2-nistp256|sk-ssh-ed25519@openssh.com|rsa-sha2-512|rsa-sha2-256|ssh-rsa)$' -or $fields[1] -notmatch '^[A-Za-z0-9+/]+={0,3}$') {
    throw 'SSH-7323 PUBLIC_KEY_INVALID: the input is not a supported OpenSSH public-key record.'
}

$fingerprintOutput = (& $sshKeygenPath -lf $PublicKeyFile -E sha256 2>$null | Select-Object -First 1)
if ($LASTEXITCODE -ne 0 -or $fingerprintOutput -notmatch '(SHA256:[A-Za-z0-9+/]{43})') {
    throw 'SSH-7323 PUBLIC_KEY_INVALID: the persisted ssh-keygen binary rejected the supplied public key.'
}
$fingerprint = $Matches[1]
$keyIdentity = "$($fields[0]) $($fields[1])"
$canonicalRecord = "$keyIdentity aegis:$DeviceId"
$stream = [IO.File]::Open($authorizedKeysPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
    $bytes = [byte[]]::new([int]$stream.Length)
    if ($bytes.Length -gt 0) { [void]$stream.Read($bytes, 0, $bytes.Length) }
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        $bytes = $bytes[3..($bytes.Length - 1)]
    }
    $content = [Text.Encoding]::UTF8.GetString($bytes)
    $existing = @($content -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $alreadyPresent = [bool]($existing | Where-Object { $_.Trim() -eq $canonicalRecord } | Select-Object -First 1)
    $sameKeyOtherDevice = $existing | Where-Object {
        ($_.Trim().StartsWith("$keyIdentity ") -or $_.Trim() -eq $keyIdentity) -and
        -not $_.TrimEnd().EndsWith(" aegis:$DeviceId", [StringComparison]::Ordinal)
    } | Select-Object -First 1
    if ($sameKeyOtherDevice) {
        throw 'SSH-7328 PUBLIC_KEY_ALREADY_BOUND: this SSH public key is already assigned to another Aegis device identity.'
    }

    # Re-pairing rotates the key for this device instead of accumulating old,
    # still-authorized credentials under the same device marker.
    $kept = @($existing | Where-Object {
        -not $_.TrimEnd().EndsWith(" aegis:$DeviceId", [StringComparison]::Ordinal)
    })
    $records = @($kept + $canonicalRecord)
    $newContent = ($records -join "`r`n") + "`r`n"
    $newBytes = [Text.UTF8Encoding]::new($false).GetBytes($newContent)
    $stream.SetLength(0)
    [void]$stream.Seek(0, [IO.SeekOrigin]::Begin)
    $stream.Write($newBytes, 0, $newBytes.Length)
    $stream.Flush($true)
} finally {
    $stream.Dispose()
}

[ordered]@{
    deviceId = $DeviceId
    fingerprint = $fingerprint
    algorithm = $fields[0]
    added = -not $alreadyPresent
    authorizedKeysPath = $authorizedKeysPath
    authorizedUser = Get-AegisSshAllowUsersName -Account ([string]$manifest.authorizedAccount)
    authorizedUserSid = [string]$manifest.authorizedSid
    instanceId = [string]$manifest.instanceId
} | ConvertTo-Json -Compress
