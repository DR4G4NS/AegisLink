[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$openSshRoot = Join-Path $PSScriptRoot '..\..\packaging\windows\openssh'
$installScript = Join-Path $openSshRoot 'install-aegis-openssh.ps1'
$inspectScript = Join-Path $openSshRoot 'inspect-aegis-openssh.ps1'
$uninstallScript = Join-Path $openSshRoot 'uninstall-aegis-openssh.ps1'

foreach ($path in @($installScript, $inspectScript, $uninstallScript)) {
    $source = Get-Content -LiteralPath $path -Raw
    if ($source -match '(?im)\bSet-NetAdapterPowerManagement\b') {
        throw "TST-7201: WOL observation script invokes a power-management setter: $path"
    }
}

$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($inspectScript, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) {
    throw "TST-7202: inspector could not be parsed: $($parseErrors[0].Message)"
}

$requiredFunctions = @('Get-Ipv4BroadcastAddress', 'Get-WakeOnLanConfigs')
$functionSource = foreach ($name in $requiredFunctions) {
    $function = $ast.FindAll(
        {
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
        },
        $true
    ) | Select-Object -First 1
    if (-not $function) { throw "TST-7203: inspector does not define $name." }
    $function.Extent.Text
}
. ([scriptblock]::Create(($functionSource -join [Environment]::NewLine)))

$script:wolFixture = $null
function Get-NetAdapter {
    [CmdletBinding()]
    param([switch]$Physical)
    return @($script:wolFixture.adapter)
}

function Get-NetIPAddress {
    [CmdletBinding()]
    param([int]$InterfaceIndex, [string]$AddressFamily)
    if ($script:wolFixture.hasIp) {
        return [pscustomobject]@{ IPAddress = '192.168.40.23'; PrefixLength = 24 }
    }
    return @()
}

function Get-NetAdapterPowerManagement {
    [CmdletBinding()]
    param([string]$Name)
    if ($script:wolFixture.queryFails) { throw 'simulated power query failure' }
    return [pscustomobject]@{ WakeOnMagicPacket = $script:wolFixture.wakeState }
}

function Get-ObservedWolConfig([string]$WakeState, [switch]$QueryFails, [switch]$WithoutIp) {
    $script:wolFixture = [pscustomobject]@{
        adapter = [pscustomobject]@{
            Name = 'Ethernet Test'
            InterfaceIndex = 7
            InterfaceGuid = '11111111-2222-3333-4444-555555555555'
            MacAddress = '00-11-22-33-44-55'
            Status = 'Down'
        }
        wakeState = $WakeState
        queryFails = $QueryFails.IsPresent
        hasIp = -not $WithoutIp.IsPresent
    }
    return @(Get-WakeOnLanConfigs)[0]
}

$enabled = Get-ObservedWolConfig 'Enabled'
if ($enabled.capability -ne 'Supported' -or $enabled.capabilityReason -ne $null) {
    throw 'TST-7204: Enabled adapter was not reported as supported.'
}
if ($enabled.macAddress.value -ne '00:11:22:33:44:55' -or
    $enabled.ipAddress -ne '192.168.40.23' -or
    $enabled.prefixLength -ne 24 -or
    $enabled.broadcastAddress -ne '192.168.40.255' -or
    $enabled.adapterStatus -ne 'Down') {
    throw 'TST-7205: observed adapter metadata is incomplete or incorrect.'
}

$disabled = Get-ObservedWolConfig 'Disabled'
if ($disabled.capability -eq 'Supported' -or
    $disabled.capability -ne 'Unknown' -or
    $disabled.capabilityReason -notmatch 'WOL-7204 MAGIC_PACKET_WAKE_DISABLED') {
    throw 'TST-7206: Disabled adapter was not reported as unavailable or unconfigured.'
}

$unsupported = Get-ObservedWolConfig 'Unsupported'
if ($unsupported.capability -ne 'Unsupported' -or
    $unsupported.capabilityReason -notmatch 'WOL-7201 ADAPTER_NOT_WAKE_CAPABLE') {
    throw 'TST-7207: Unsupported adapter was not reported as unsupported.'
}

$queryFailed = Get-ObservedWolConfig 'Enabled' -QueryFails -WithoutIp
if ($queryFailed.capability -ne 'Unknown' -or
    $queryFailed.capabilityReason -notmatch 'WOL-7203 CAPABILITY_QUERY_UNAVAILABLE' -or
    $queryFailed.capabilityReason -notmatch 'WOL-7205 DIRECTED_BROADCAST_UNAVAILABLE') {
    throw 'TST-7208: failed WOL query was not reported as unknown with diagnostics.'
}

Write-Host 'WOL observation tests passed: no provisioning setter; Enabled, Disabled, Unsupported, and query-failure payloads are classified honestly.' -ForegroundColor Green
