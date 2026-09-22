[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9._:-]{1,128}$')]
    [string]$DeviceId
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$managedInstanceHelper = Join-Path $PSScriptRoot 'aegis-openssh-managed-instance.ps1'
if (-not (Test-Path -LiteralPath $managedInstanceHelper -PathType Leaf)) {
    throw 'SSH-7329 MANAGED_HELPER_MISSING: the managed-instance validator was not packaged with key removal.'
}
. $managedInstanceHelper

$manifest = Read-AegisOpenSshManifest -RequireBinaries -RequireInstanceFiles -RequireConfiguration
$service = Get-CimInstance Win32_Service -Filter "Name='AegisOpenSSH'" -ErrorAction SilentlyContinue
if (-not $service) {
    throw 'SSH-7319 INSTANCE_NOT_PROVISIONED: the isolated Aegis service does not exist.'
}
Assert-AegisManagedServiceOwnership -Service $service -Manifest $manifest
Assert-AegisManagedCaller -Manifest $manifest
$authorizedKeysPath = [string]$manifest.authorizedKeysPath

$marker = "aegis:$DeviceId"
$stream = [IO.File]::Open($authorizedKeysPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
    $bytes = [byte[]]::new([int]$stream.Length)
    if ($bytes.Length -gt 0) { [void]$stream.Read($bytes, 0, $bytes.Length) }
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        $bytes = $bytes[3..($bytes.Length - 1)]
    }
    $content = [Text.Encoding]::UTF8.GetString($bytes)
    $records = @($content -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $kept = @($records | Where-Object { -not $_.TrimEnd().EndsWith(" $marker", [StringComparison]::Ordinal) })
    $removed = $kept.Count -ne $records.Count
    $newContent = if ($kept.Count -eq 0) { '' } else { ($kept -join "`r`n") + "`r`n" }
    $newBytes = [Text.UTF8Encoding]::new($false).GetBytes($newContent)
    $stream.SetLength(0)
    [void]$stream.Seek(0, [IO.SeekOrigin]::Begin)
    if ($newBytes.Length -gt 0) { $stream.Write($newBytes, 0, $newBytes.Length) }
    $stream.Flush($true)
} finally {
    $stream.Dispose()
}

[ordered]@{
    deviceId = $DeviceId
    removed = $removed
    authorizedKeysPath = $authorizedKeysPath
    authorizedUser = [string]$manifest.authorizedAccount
    authorizedUserSid = [string]$manifest.authorizedSid
    instanceId = [string]$manifest.instanceId
} | ConvertTo-Json -Compress
