[CmdletBinding()]
param(
    [switch]$RemoveCapabilityInstalledByAegis
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'SSH-7306 ADMINISTRATOR_REQUIRED: removing the isolated Aegis OpenSSH service requires elevation.'
}

$managedInstanceHelper = Join-Path $PSScriptRoot 'aegis-openssh-managed-instance.ps1'
if (-not (Test-Path -LiteralPath $managedInstanceHelper -PathType Leaf)) {
    throw 'SSH-7329 MANAGED_HELPER_MISSING: the managed-instance validator was not packaged with the uninstaller.'
}
. $managedInstanceHelper

$serviceName = 'AegisOpenSSH'
$firewallRuleId = $script:AegisOpenSshFirewallRuleId
$firewallRuleName = $script:AegisOpenSshFirewallDisplayName
$firewallGroup = $script:AegisFirewallGroup
$layout = Get-AegisOpenSshLayout
$rootDirectory = $layout.rootDirectory
$manifestPath = $layout.manifestPath
$capabilityMarkerPath = $layout.capabilityMarkerPath
$service = Get-CimInstance Win32_Service -Filter "Name='$serviceName'" -ErrorAction SilentlyContinue
$firewallRules = @(Get-NetFirewallRule -Name $firewallRuleId -ErrorAction SilentlyContinue)

if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    if ($service -or (Test-Path -LiteralPath $rootDirectory) -or $firewallRules.Count -gt 0) {
        throw 'SSH-7329 MANAGED_MANIFEST_MISSING: refusing to remove isolated artifacts that have no managed manifest.'
    }
    [ordered]@{
        serviceName = $serviceName
        removed = $true
        rootDirectory = $rootDirectory
        firewallRuleName = $firewallRuleName
        capabilityRemoved = $false
    } | ConvertTo-Json -Compress
    return
}

$manifest = Read-AegisOpenSshManifest -RequireInstanceFiles -RequireConfiguration
Assert-AegisManagedServiceOwnership -Service $service -Manifest $manifest
if ($firewallRules.Count -gt 0) {
    Assert-AegisManagedFirewallRuleOwnership `
        -Rules $firewallRules `
        -RuleId ([string]$manifest.firewallRuleId) `
        -DisplayName ([string]$manifest.firewallDisplayName) `
        -Group ([string]$manifest.firewallGroup) `
        -Program ([string]$manifest.sshdPath) `
        -Port ([int]$manifest.port)
}
$rootDirectory = [string]$manifest.rootDirectory
$capabilityMarkerPath = Join-Path $rootDirectory 'openssh-capability-installed-by-aegis'

if ($service) {
    if ($service.State -ne 'Stopped') {
        Stop-Service -Name $serviceName -Force
        (Get-Service -Name $serviceName).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(20))
    }
    & "$env:WINDIR\System32\sc.exe" delete $serviceName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "SSH-7324 SERVICE_DELETE_FAILED: sc.exe returned $LASTEXITCODE." }
}

Remove-AegisManagedFirewallRule `
    -RuleId ([string]$manifest.firewallRuleId) `
    -DisplayName ([string]$manifest.firewallDisplayName) `
    -Group ([string]$manifest.firewallGroup) `
    -Program ([string]$manifest.sshdPath) `
    -Port ([int]$manifest.port)

$expectedRoot = (Get-AegisOpenSshLayout).rootDirectory
if (-not (Test-AegisPathEqual $rootDirectory $expectedRoot)) {
    throw "SSH-7325 MANAGED_ROOT_MISMATCH: refusing to delete unexpected path $rootDirectory."
}
Assert-AegisPathNotReparsePoint -Path $rootDirectory -FailureName 'managed root'
$removeCapability = $RemoveCapabilityInstalledByAegis -and (Test-Path -LiteralPath $capabilityMarkerPath -PathType Leaf)
if ($removeCapability) {
    $stockSshd = Get-CimInstance Win32_Service -Filter "Name='sshd'" -ErrorAction SilentlyContinue
    $stockRoot = Join-Path $env:ProgramData 'ssh'
    $stockArtifacts = Test-Path -LiteralPath $stockRoot
    $stockServiceAdopted = $stockSshd -and ($stockSshd.State -ne 'Stopped' -or $stockSshd.StartMode -notin @('Manual', 'Disabled'))
    if ($stockArtifacts -or $stockServiceAdopted) {
        # The capability is shared system state. Preserve it if the user has
        # since created stock configuration/keys or enabled its stock service.
        $removeCapability = $false
    }
}
if (Test-Path -LiteralPath $rootDirectory) {
    $resolvedDeleteTarget = [IO.Path]::GetFullPath($rootDirectory)
    if (-not (Test-AegisPathEqual $resolvedDeleteTarget $expectedRoot)) {
        throw "SSH-7325 MANAGED_ROOT_MISMATCH: refusing recursive deletion of $resolvedDeleteTarget."
    }
    Remove-Item -LiteralPath $resolvedDeleteTarget -Recurse -Force
}

$capabilityRemoved = $false
if ($removeCapability) {
    $capability = Get-WindowsCapability -Online -Name 'OpenSSH.Server*' | Select-Object -First 1
    if ($capability -and $capability.State -eq 'Installed') {
        Remove-WindowsCapability -Online -Name $capability.Name | Out-Null
        $capabilityRemoved = $true
    }
}

[ordered]@{
    serviceName = $serviceName
    removed = $true
    rootDirectory = $rootDirectory
    firewallRuleName = $firewallRuleName
    authorizedUser = [string]$manifest.authorizedAccount
    authorizedUserSid = [string]$manifest.authorizedSid
    instanceId = [string]$manifest.instanceId
    sshdPath = [string]$manifest.sshdPath
    capabilityRemoved = $capabilityRemoved
} | ConvertTo-Json -Compress
