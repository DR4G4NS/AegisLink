[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('Install', 'Uninstall')]
    [string]$Mode
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$managedInstanceHelper = Join-Path $PSScriptRoot 'aegis-openssh-managed-instance.ps1'
if (-not (Test-Path -LiteralPath $managedInstanceHelper -PathType Leaf)) {
    throw 'PKG-9025 PAIRING_FIREWALL_HELPER_MISSING: the managed firewall validator was not packaged.'
}

. $managedInstanceHelper

$ruleId = 'Aegis.RemoteDesktop.Pairing.Firewall.v1'
$displayName = 'Aegis Remote Desktop Pairing'
$installRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$program = Join-Path $installRoot 'Aegis Remote Desktop.exe'
if (-not (Test-Path -LiteralPath $program -PathType Leaf)) {
    throw 'PKG-9026 PAIRING_FIREWALL_PROGRAM_MISSING: the installed Aegis launcher is unavailable.'
}

if ($Mode -eq 'Install') {
    Install-AegisManagedFirewallRule `
        -RuleId $ruleId `
        -DisplayName $displayName `
        -Group $script:AegisFirewallGroup `
        -Program $program `
        -Port 48291
} else {
    Remove-AegisManagedFirewallRule `
        -RuleId $ruleId `
        -DisplayName $displayName `
        -Group $script:AegisFirewallGroup `
        -Program $program `
        -Port 48291
}

# The app owns network access; the installed service listens on loopback only.
$manifest = Read-AegisOpenSshManifest -RequireConfiguration
$gatewayArguments = @{
    RuleId = 'Aegis.RemoteDesktop.Access.Firewall.v1'
    DisplayName = 'Aegis Remote Desktop Access'
    Group = $script:AegisFirewallGroup
    Program = $program
    Port = [int]$manifest.port
}
if ($Mode -eq 'Install') {
    Install-AegisManagedFirewallRule @gatewayArguments
} else {
    Remove-AegisManagedFirewallRule @gatewayArguments
}
