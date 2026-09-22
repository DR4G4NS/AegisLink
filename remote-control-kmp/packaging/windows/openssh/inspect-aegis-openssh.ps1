[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$managedInstanceHelper = Join-Path $PSScriptRoot 'aegis-openssh-managed-instance.ps1'
if (-not (Test-Path -LiteralPath $managedInstanceHelper -PathType Leaf)) {
    throw 'SSH-7329 MANAGED_HELPER_MISSING: the managed-instance validator was not packaged with the inspector.'
}
. $managedInstanceHelper

$serviceName = 'AegisOpenSSH'
$firewallRuleId = $script:AegisOpenSshFirewallRuleId
$firewallRuleName = $script:AegisOpenSshFirewallDisplayName
$firewallGroup = $script:AegisFirewallGroup
$layout = Get-AegisOpenSshLayout
$rootDirectory = $layout.rootDirectory
$manifestPath = $layout.manifestPath
$service = Get-CimInstance Win32_Service -Filter "Name='$serviceName'" -ErrorAction SilentlyContinue
$firewallRules = @(Get-NetFirewallRule -Name $firewallRuleId -ErrorAction SilentlyContinue)

function Get-Ipv4BroadcastAddress([string]$Address, [int]$PrefixLength) {
    if ($PrefixLength -lt 0 -or $PrefixLength -gt 32) { return '255.255.255.255' }
    $addressBytes = [Net.IPAddress]::Parse($Address).GetAddressBytes()
    $broadcastBytes = [byte[]]::new(4)
    for ($index = 0; $index -lt 4; $index++) {
        $remainingBits = $PrefixLength - ($index * 8)
        $mask = if ($remainingBits -ge 8) { 255 } elseif ($remainingBits -le 0) { 0 } else { (256 - [Math]::Pow(2, 8 - $remainingBits)) }
        $broadcastBytes[$index] = [byte](([int]$addressBytes[$index]) -bor ((-bnot [int]$mask) -band 255))
    }
    return [Net.IPAddress]::new($broadcastBytes).ToString()
}

function Get-WakeOnLanConfigs {
    $configs = @()
    # Inspection is observational. Include every physical adapter and expose its
    # reported state so a down adapter is not silently treated as unavailable.
    $adapters = @(Get-NetAdapter -Physical -ErrorAction SilentlyContinue)
    foreach ($adapter in $adapters) {
        if ([string]::IsNullOrWhiteSpace($adapter.MacAddress)) { continue }
        $ipAddress = Get-NetIPAddress -InterfaceIndex $adapter.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.IPAddress -notlike '169.254.*' } |
            Sort-Object PrefixLength -Descending |
            Select-Object -First 1
        $broadcastAddress = if ($ipAddress) { Get-Ipv4BroadcastAddress $ipAddress.IPAddress $ipAddress.PrefixLength } else { '255.255.255.255' }
        $capability = 'Unknown'
        $capabilityReason = 'WOL-7203 CAPABILITY_QUERY_UNAVAILABLE'
        try {
            $power = Get-NetAdapterPowerManagement -Name $adapter.Name -ErrorAction Stop
            $magicPacket = [string]$power.WakeOnMagicPacket
            if ($magicPacket -eq 'Enabled') {
                $capability = 'Supported'
                $capabilityReason = $null
            } elseif ($magicPacket -eq 'Unsupported') {
                $capability = 'Unsupported'
                $capabilityReason = 'WOL-7201 ADAPTER_NOT_WAKE_CAPABLE'
            } elseif ($magicPacket -eq 'Disabled') {
                $capabilityReason = 'WOL-7204 MAGIC_PACKET_WAKE_DISABLED'
            }
        } catch {
            $capabilityReason = "WOL-7203 CAPABILITY_QUERY_UNAVAILABLE: $($_.Exception.GetType().Name)"
        }
        if (-not $ipAddress) {
            $capabilityReason = (@($capabilityReason, 'WOL-7205 DIRECTED_BROADCAST_UNAVAILABLE') | Where-Object { $_ }) -join '; '
        }
        $configs += [pscustomobject]@{
            macAddress = [pscustomobject]@{ value = $adapter.MacAddress.Replace('-', ':').ToUpperInvariant() }
            ipAddress = if ($ipAddress) { [string]$ipAddress.IPAddress } else { $null }
            prefixLength = if ($ipAddress) { [int]$ipAddress.PrefixLength } else { $null }
            broadcastAddress = $broadcastAddress
            port = 9
            adapterId = [string]$adapter.InterfaceGuid
            adapterName = [string]$adapter.Name
            adapterStatus = [string]$adapter.Status
            capability = $capability
            capabilityReason = $capabilityReason
        }
    }
    return $configs
}

function Test-ManagedFirewall([object]$Rule, [object]$Manifest) {
    if (-not $Rule -or $firewallRules.Count -ne 1) { return $false }
    return (Test-AegisManagedFirewallRuleOwnership `
        -Rule $Rule `
        -RuleId ([string]$Manifest.firewallRuleId) `
        -DisplayName ([string]$Manifest.firewallDisplayName) `
        -Group ([string]$Manifest.firewallGroup) `
        -Program ([string]$Manifest.sshdPath) `
        -Port ([int]$Manifest.port))
}

$manifestExists = Test-Path -LiteralPath $manifestPath -PathType Leaf
if (-not $manifestExists) {
    if ($service -or (Test-Path -LiteralPath $rootDirectory) -or $firewallRules.Count -gt 0) {
        throw 'SSH-7329 MANAGED_MANIFEST_MISSING: isolated OpenSSH artifacts exist without a managed manifest.'
    }
    [ordered]@{
        serviceName = $serviceName
        serviceStatus = 'NotInstalled'
        port = 0
        rootDirectory = $rootDirectory
        configPath = $layout.configPath
        authorizedKeysPath = $layout.authorizedKeysPath
        hostKeyPath = $layout.hostKeyPath
        hostKeyFingerprint = $null
        hostKeyAlgorithm = $null
        firewallRuleId = $firewallRuleId
        firewallRuleName = $firewallRuleName
        sshdPath = $null
        authorizedUser = ''
        authorizedUserSid = ''
        manifestPath = $manifestPath
        manifestSchemaVersion = 0
        instanceId = $null
        firewallEnabled = $false
        wakeOnLanConfigs = @(Get-WakeOnLanConfigs)
    } | ConvertTo-Json -Compress -Depth 8
    return
}

$manifest = Read-AegisOpenSshManifest -RequireBinaries -RequireInstanceFiles -RequireConfiguration
Assert-AegisManagedServiceOwnership -Service $service -Manifest $manifest
$fingerprint = Get-AegisHostKeyFingerprint -SshKeygenPath ([string]$manifest.sshKeygenPath) -HostPublicKeyPath ([string]$manifest.hostPublicKeyPath)
$firewall = if ($firewallRules.Count -eq 1) { $firewallRules[0] } else { $null }

[ordered]@{
    serviceName = $serviceName
    serviceStatus = if ($service) { $service.State } else { 'NotInstalled' }
    port = [int]$manifest.port
    appScopedAccess = [bool]($manifest.PSObject.Properties.Name -contains 'appScopedAccess' -and $manifest.appScopedAccess)
    rootDirectory = [string]$manifest.rootDirectory
    configPath = [string]$manifest.configPath
    authorizedKeysPath = [string]$manifest.authorizedKeysPath
    hostKeyPath = [string]$manifest.hostKeyPath
    hostKeyFingerprint = $fingerprint
    hostKeyAlgorithm = 'ssh-ed25519'
    firewallRuleName = $firewallRuleName
    sshdPath = [string]$manifest.sshdPath
    authorizedUser = Get-AegisSshAllowUsersName -Account ([string]$manifest.authorizedAccount)
    authorizedUserSid = [string]$manifest.authorizedSid
    manifestPath = $manifestPath
    manifestSchemaVersion = [int]$manifest.schemaVersion
    instanceId = [string]$manifest.instanceId
    firewallEnabled = Test-ManagedFirewall -Rule $firewall -Manifest $manifest
    wakeOnLanConfigs = @(Get-WakeOnLanConfigs)
} | ConvertTo-Json -Compress -Depth 8
