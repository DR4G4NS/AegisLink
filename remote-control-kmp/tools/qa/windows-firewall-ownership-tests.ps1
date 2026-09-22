[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$helper = Join-Path $PSScriptRoot '..\..\packaging\windows\openssh\aegis-openssh-managed-instance.ps1'
. $helper

$script:rules = [System.Collections.Generic.List[object]]::new()
$script:removedRuleIds = [System.Collections.Generic.List[string]]::new()

function New-FakeFirewallRule {
    param(
        [string]$Name,
        [string]$DisplayName,
        [string]$Group,
        [string]$Program,
        [int]$Port,
        [string]$Action = 'Allow',
        [string]$Profile = 'Domain, Private'
    )
    return [pscustomobject]@{
        Name = $Name
        DisplayName = $DisplayName
        Group = $Group
        Enabled = 'True'
        Direction = 'Inbound'
        Action = $Action
        EdgeTraversalPolicy = 'Block'
        Profile = $Profile
        Program = $Program
        Port = [string]$Port
    }
}

function Get-NetFirewallRule {
    param([string]$Name, [string]$DisplayName)
    if ($PSBoundParameters.ContainsKey('Name')) {
        return @($script:rules | Where-Object { $_.Name -eq $Name })
    }
    return @($script:rules | Where-Object { $_.DisplayName -eq $DisplayName })
}

function Get-NetFirewallPortFilter {
    param([Parameter(Mandatory)][object]$AssociatedNetFirewallRule)
    return [pscustomobject]@{ Protocol = 'TCP'; LocalPort = $AssociatedNetFirewallRule.Port }
}

function Get-NetFirewallApplicationFilter {
    param([Parameter(Mandatory)][object]$AssociatedNetFirewallRule)
    return [pscustomobject]@{ Program = $AssociatedNetFirewallRule.Program }
}

function Remove-NetFirewallRule {
    param([Parameter(ValueFromPipeline = $true, Mandatory = $true)][object]$InputObject)
    process {
        [void]$script:removedRuleIds.Add([string]$InputObject.Name)
        [void]$script:rules.Remove($InputObject)
    }
}

function New-NetFirewallRule {
    param(
        [string]$Name,
        [string]$DisplayName,
        [string]$Group,
        [string]$Direction,
        [string]$Action,
        [object]$Enabled,
        [object]$Profile,
        [string]$Protocol,
        [int]$LocalPort,
        [string]$Program,
        [string]$EdgeTraversalPolicy
    )
    $profileText = @($Profile) -join ', '
    $rule = New-FakeFirewallRule -Name $Name -DisplayName $DisplayName -Group $Group -Program $Program -Port $LocalPort -Action $Action -Profile $profileText
    $script:rules.Add($rule)
    return $rule
}

$ruleId = 'Aegis.OpenSSH.Firewall.v1'
$displayName = 'Aegis OpenSSH (Private and Domain)'
$group = 'Aegis Remote Control'
$program = 'C:\Aegis\OpenSSH\sshd.exe'
$port = 48222
$foreign = New-FakeFirewallRule -Name 'Foreign.Collision' -DisplayName $displayName -Group 'Foreign' -Program 'C:\Windows\System32\cmd.exe' -Port 48223 -Action 'Block' -Profile 'Private'
$script:rules.Add($foreign)

Install-AegisManagedFirewallRule -RuleId $ruleId -DisplayName $displayName -Group $group -Program $program -Port $port
if ($script:rules.Count -ne 2 -or -not ($script:rules -contains $foreign)) {
    throw 'TST-9035: install did not preserve the foreign DisplayName collision.'
}
$managed = @($script:rules | Where-Object { $_.Name -eq $ruleId })
Assert-AegisManagedFirewallRuleOwnership -Rules $managed -RuleId $ruleId -DisplayName $displayName -Group $group -Program $program -Port $port

Install-AegisManagedFirewallRule -RuleId $ruleId -DisplayName $displayName -Group $group -Program $program -Port $port
if ($script:rules.Count -ne 2 -or -not ($script:rules -contains $foreign) -or $script:removedRuleIds.Count -ne 1 -or $script:removedRuleIds[0] -ne $ruleId) {
    throw 'TST-9036: repair did not replace only the proven managed rule.'
}

Remove-AegisManagedFirewallRule -RuleId $ruleId -DisplayName $displayName -Group $group -Program $program -Port $port
if ($script:rules.Count -ne 1 -or -not ($script:rules -contains $foreign)) {
    throw 'TST-9037: uninstall did not preserve the foreign DisplayName collision.'
}

$collision = New-FakeFirewallRule -Name $ruleId -DisplayName $displayName -Group $group -Program 'C:\Windows\System32\cmd.exe' -Port $port
$script:rules.Add($collision)
$removedBeforeAmbiguity = $script:removedRuleIds.Count
try {
    Remove-AegisManagedFirewallRule -RuleId $ruleId -DisplayName $displayName -Group $group -Program $program -Port $port
    throw 'TST-9038: uninstall accepted a technical-ID collision with mismatched filters.'
} catch {
    if ($_.Exception.Message -notmatch 'SSH-7333 FIREWALL_OWNERSHIP_MISMATCH') { throw }
}
if (-not ($script:rules -contains $collision) -or $script:removedRuleIds.Count -ne $removedBeforeAmbiguity) {
    throw 'TST-9039: ambiguous technical-ID collision was modified.'
}

Write-Host 'Firewall ownership simulation passed: DisplayName collisions are preserved; repair/uninstall require exact technical ID and filters.' -ForegroundColor Green
