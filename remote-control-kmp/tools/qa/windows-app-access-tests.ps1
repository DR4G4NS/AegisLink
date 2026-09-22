[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('AegisAccessTest-' + [Guid]::NewGuid().ToString('N'))
$expectedTestRoot = [IO.Path]::GetFullPath($testRoot)
New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
    $source = Join-Path $PSScriptRoot '..\..\packaging\windows\openssh'
    foreach ($file in @('set-aegis-ssh-access.ps1', 'remove-aegis-ssh-key.ps1')) {
        Copy-Item -LiteralPath (Join-Path $source $file) -Destination (Join-Path $testRoot $file)
    }
    # Only OS ownership lookups are mocked; the shipped key mutation scripts run unchanged.
    $helper = @'
function Read-AegisOpenSshManifest {
    param([switch]$RequireBinaries, [switch]$RequireInstanceFiles, [switch]$RequireConfiguration)
    [pscustomobject]@{ authorizedKeysPath = (Join-Path $PSScriptRoot 'authorized_keys'); authorizedAccount = 'test'; authorizedSid = 'test'; instanceId = 'test' }
}
function Assert-AegisManagedCaller { param($Manifest) }
function Assert-AegisManagedServiceOwnership { param($Service, $Manifest) }
function Get-CimInstance { param($ClassName, $Filter, $ErrorAction) [pscustomobject]@{ State = 'Running' } }
'@
    [IO.File]::WriteAllText((Join-Path $testRoot 'aegis-openssh-managed-instance.ps1'), $helper)
    $keys = Join-Path $testRoot 'authorized_keys'
    $first = 'ssh-ed25519 AAAATEST1 aegis:phone-1'
    $second = 'ssh-ed25519 AAAATEST2 aegis:phone-2'
    [IO.File]::WriteAllLines($keys, @($first, $second))
    $setAccess = Join-Path $testRoot 'set-aegis-ssh-access.ps1'
    & $setAccess -DeviceId 'phone-1' -Access Deny | Out-Null
    $lines = @(Get-Content -LiteralPath $keys)
    if ($lines[0] -ne "# aegis-disabled:phone-1 $first" -or $lines[1] -ne $second) { throw 'Deny changed the wrong key or left access enabled.' }
    & $setAccess -DeviceId 'phone-1' -Access Allow | Out-Null
    if (@(Get-Content -LiteralPath $keys)[0] -ne $first) { throw 'Allow did not restore the exact enrolled key.' }
    & $setAccess -DeviceId 'phone-1' -Access Deny | Out-Null
    & (Join-Path $testRoot 'remove-aegis-ssh-key.ps1') -DeviceId 'phone-1' | Out-Null
    if ((Get-Content -LiteralPath $keys -Raw).Trim() -ne $second) { throw 'Revocation did not remove the disabled key.' }
    try { & $setAccess -DeviceId 'missing-phone' -Access Allow | Out-Null; throw 'Missing key was accepted.' }
    catch { if ($_.Exception.Message -notlike '*SSH-7335*') { throw } }
    'PASS: allow, deny, exact device isolation, revoke after deny, and missing-key rejection.'
} finally {
    $resolved = (Resolve-Path -LiteralPath $testRoot).Path
    if ($resolved -ne $expectedTestRoot -or -not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing cleanup outside the test directory.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
