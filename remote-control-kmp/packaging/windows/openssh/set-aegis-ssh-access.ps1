[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._:-]{1,128}$')][string]$DeviceId,
    [Parameter(Mandatory)][ValidateSet('Allow', 'Deny')][string]$Access
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'aegis-openssh-managed-instance.ps1')
$manifest = Read-AegisOpenSshManifest -RequireBinaries -RequireInstanceFiles -RequireConfiguration
Assert-AegisManagedCaller -Manifest $manifest
$service = Get-CimInstance Win32_Service -Filter "Name='AegisOpenSSH'" -ErrorAction Stop
Assert-AegisManagedServiceOwnership -Service $service -Manifest $manifest
$path = [string]$manifest.authorizedKeysPath
$marker = " aegis:$DeviceId"
$disabledMarker = "# aegis-disabled:$DeviceId "
$stream = [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
    $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8, $true, 4096, $true)
    try { $content = $reader.ReadToEnd() } finally { $reader.Dispose() }
    $found = $false
    $records = @($content -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object {
        $record = $_.Trim()
        if ($record.StartsWith($disabledMarker, [StringComparison]::Ordinal)) { $record = $record.Substring($disabledMarker.Length) }
        if ($record.EndsWith($marker, [StringComparison]::Ordinal)) {
            if ($record -notmatch '^(ssh-ed25519|ecdsa-sha2-nistp256|sk-ssh-ed25519@openssh.com|rsa-sha2-512|rsa-sha2-256|ssh-rsa) [A-Za-z0-9+/]+=* aegis:') {
                throw 'SSH-7335: The managed key record is invalid.'
            }
            $found = $true
            if ($Access -eq 'Deny') { $disabledMarker + $record } else { $record }
        } else { $_ }
    })
    if (-not $found) { throw 'SSH-7335: Pair this phone again before enabling terminal and files.' }
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($records -join "`r`n") + "`r`n")
    $stream.SetLength(0)
    [void]$stream.Seek(0, [IO.SeekOrigin]::Begin)
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Flush($true)
} finally { $stream.Dispose() }
[ordered]@{ deviceId = $DeviceId; removed = ($Access -eq 'Deny'); authorizedKeysPath = $path } | ConvertTo-Json -Compress
