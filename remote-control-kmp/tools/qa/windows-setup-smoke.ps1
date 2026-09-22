[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SetupPath,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedSignerSubject,

    [switch]$KeepInstalled
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'PKG-9010: the Windows Setup smoke test requires an elevated release runner.'
}

$setup = Get-Item -LiteralPath (Resolve-Path -LiteralPath $SetupPath).Path -Force
if ($setup.PSIsContainer -or ($setup.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'PKG-9005: Setup smoke input must be a regular file.'
}
$signature = Get-AuthenticodeSignature -LiteralPath $setup.FullName
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -ne $ExpectedSignerSubject) {
    throw "PKG-9006: Setup signer is invalid or unexpected (status=$($signature.Status))."
}

$installRoot = Join-Path $env:ProgramFiles 'Aegis Remote Desktop'
$launcher = Join-Path $installRoot 'Aegis Remote Desktop.exe'
$uninstaller = Join-Path $installRoot 'unins000.exe'
$openSshScriptRoot = Join-Path $installRoot 'provisioning\openssh'
$inspectScript = Join-Path $openSshScriptRoot 'inspect-aegis-openssh.ps1'
$enrollKeyScript = Join-Path $openSshScriptRoot 'enroll-aegis-ssh-key.ps1'
$removeKeyScript = Join-Path $openSshScriptRoot 'remove-aegis-ssh-key.ps1'
$pairingFirewallScript = Join-Path $openSshScriptRoot 'manage-aegis-pairing-firewall.ps1'
$identityFile = Join-Path $HOME '.aegis\desktop-device-identity.json'
$pairingFirewallId = 'Aegis.RemoteDesktop.Pairing.Firewall.v1'
$pairingFirewallName = 'Aegis Remote Desktop Pairing'
$sshFirewallId = 'Aegis.OpenSSH.Firewall.v1'
$sshFirewallName = 'Aegis OpenSSH (Private and Domain)'
$firewallGroup = 'Aegis Remote Control'
$qaCollisionSuffix = [Guid]::NewGuid().ToString('N')
$foreignPairingFirewallId = "Aegis.Qa.Foreign.Pairing.$qaCollisionSuffix"
$foreignSshFirewallId = "Aegis.Qa.Foreign.OpenSsh.$qaCollisionSuffix"
$logRoot = Join-Path $env:RUNNER_TEMP 'aegis-setup-smoke'
$qaRoot = Join-Path $logRoot 'openssh-protocol'
$qaDeviceId = 'qa-setup-smoke'
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null

function Get-StockOpenSshSnapshot {
    $capability = Get-WindowsCapability -Online -Name 'OpenSSH.Server*' -ErrorAction SilentlyContinue |
        Sort-Object Name |
        Select-Object -First 1
    $service = Get-CimInstance Win32_Service -Filter "Name='sshd'" -ErrorAction SilentlyContinue
    $stockRoot = Join-Path $env:ProgramData 'ssh'
    $trackedPaths = @(
        (Join-Path $stockRoot 'sshd_config'),
        (Join-Path $stockRoot 'ssh_host_ed25519_key'),
        (Join-Path $stockRoot 'ssh_host_ed25519_key.pub'),
        (Join-Path $stockRoot 'ssh_host_ecdsa_key'),
        (Join-Path $stockRoot 'ssh_host_ecdsa_key.pub'),
        (Join-Path $stockRoot 'ssh_host_rsa_key'),
        (Join-Path $stockRoot 'ssh_host_rsa_key.pub')
    )
    $files = @(
        foreach ($path in $trackedPaths) {
            if (Test-Path -LiteralPath $path -PathType Leaf) {
                [pscustomobject][ordered]@{
                    path = [IO.Path]::GetFullPath($path)
                    sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
                }
            }
        }
    )
    return [pscustomobject][ordered]@{
        capabilityName = if ($capability) { [string]$capability.Name } else { $null }
        capabilityState = if ($capability) { [string]$capability.State } else { 'Unavailable' }
        serviceExists = [bool]$service
        servicePath = if ($service) { [string]$service.PathName } else { $null }
        serviceStartMode = if ($service) { [string]$service.StartMode } else { $null }
        files = $files
    }
}

function Assert-StockOpenSshPreserved([object]$Before) {
    $after = Get-StockOpenSshSnapshot
    if ($Before.capabilityState -eq 'Installed' -and $after.capabilityState -ne 'Installed') {
        throw 'SSH-9021: uninstall removed the pre-existing Windows OpenSSH Server capability.'
    }
    if ($Before.serviceExists) {
        if (-not $after.serviceExists -or $after.servicePath -ne $Before.servicePath -or $after.serviceStartMode -ne $Before.serviceStartMode) {
            throw 'SSH-9022: the stock Windows sshd service changed during the Aegis lifecycle.'
        }
    } elseif ($after.serviceExists) {
        throw 'SSH-9022: Aegis left a stock sshd service that did not exist before installation.'
    }
    foreach ($file in @($Before.files)) {
        if (-not (Test-Path -LiteralPath $file.path -PathType Leaf)) {
            throw "SSH-9023: a pre-existing stock OpenSSH file was removed: $($file.path)"
        }
        $afterHash = (Get-FileHash -LiteralPath $file.path -Algorithm SHA256).Hash
        if ($afterHash -ne $file.sha256) {
            throw "SSH-9023: a pre-existing stock OpenSSH file changed: $($file.path)"
        }
    }
}

function Invoke-Setup([string]$Label) {
    $log = Join-Path $logRoot "$Label.log"
    $process = Start-Process -FilePath $setup.FullName -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', "/LOG=$log") -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        throw "PKG-9011: $Label Setup exited with code $($process.ExitCode). Evidence: $log"
    }
}

function Stop-AegisLauncher {
    Get-Process -Name 'Aegis Remote Desktop' -ErrorAction SilentlyContinue | Stop-Process -Force
}

function Start-AgentAndAwaitIdentity {
    Stop-AegisLauncher
    $process = Start-Process -FilePath $launcher -PassThru -WindowStyle Hidden
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds(30)
        while (-not (Test-Path -LiteralPath $identityFile -PathType Leaf) -and [DateTime]::UtcNow -lt $deadline) {
            Start-Sleep -Milliseconds 250
        }
        if (-not (Test-Path -LiteralPath $identityFile -PathType Leaf)) {
            throw 'IDN-9012: the installed agent did not persist its protected Windows identity.'
        }
    }
    finally {
        if (-not $process.HasExited) { $process.Kill($true) }
        $process.Dispose()
    }
}

function Assert-PrivateFirewallRule(
    [string]$RuleId,
    [string]$DisplayName,
    [string]$ExpectedProgram,
    [int]$ExpectedPort
) {
    $rules = @(Get-NetFirewallRule -Name $RuleId -ErrorAction Stop)
    if ($rules.Count -ne 1) {
        throw "PKG-9013: expected exactly one firewall rule ID '$RuleId', found $($rules.Count)."
    }
    $rule = $rules[0]
    $profileText = $rule.Profile.ToString()
    if ($rule.DisplayName -ne $DisplayName -or $rule.Group -ne $firewallGroup -or $rule.EdgeTraversalPolicy -ne 'Block' -or
        $profileText -match 'Public|Any' -or $profileText -notmatch 'Private' -or $profileText -notmatch 'Domain' -or
        $rule.Enabled -ne 'True' -or $rule.Direction -ne 'Inbound' -or $rule.Action -ne 'Allow') {
        throw "PKG-9013: '$RuleId' is not the expected managed private/domain-only inbound allow rule."
    }
    $portFilter = Get-NetFirewallPortFilter -AssociatedNetFirewallRule $rule -ErrorAction Stop | Select-Object -First 1
    $applicationFilter = Get-NetFirewallApplicationFilter -AssociatedNetFirewallRule $rule -ErrorAction Stop | Select-Object -First 1
    $actualProgram = [IO.Path]::GetFullPath([Environment]::ExpandEnvironmentVariables([string]$applicationFilter.Program))
    $expectedProgramPath = [IO.Path]::GetFullPath($ExpectedProgram)
    if ([string]$portFilter.Protocol -notin @('6', 'TCP') -or [string]$portFilter.LocalPort -ne [string]$ExpectedPort) {
        throw "PKG-9013: '$RuleId' does not target TCP port $ExpectedPort."
    }
    if (-not $actualProgram.Equals($expectedProgramPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw "PKG-9013: '$RuleId' targets an unexpected executable."
    }
}

function Get-FirewallRuleSnapshot([string]$RuleId) {
    $rules = @(Get-NetFirewallRule -Name $RuleId -ErrorAction Stop)
    if ($rules.Count -ne 1) { throw "PKG-9035: expected exactly one QA collision rule ID '$RuleId'." }
    $rule = $rules[0]
    $portFilter = Get-NetFirewallPortFilter -AssociatedNetFirewallRule $rule -ErrorAction Stop | Select-Object -First 1
    $applicationFilter = Get-NetFirewallApplicationFilter -AssociatedNetFirewallRule $rule -ErrorAction Stop | Select-Object -First 1
    return [pscustomobject][ordered]@{
        name = [string]$rule.Name
        displayName = [string]$rule.DisplayName
        group = [string]$rule.Group
        enabled = [string]$rule.Enabled
        direction = [string]$rule.Direction
        action = [string]$rule.Action
        profile = [string]$rule.Profile
        edgeTraversalPolicy = [string]$rule.EdgeTraversalPolicy
        protocol = [string]$portFilter.Protocol
        localPort = [string]$portFilter.LocalPort
        program = [string]$applicationFilter.Program
    }
}

function Assert-FirewallRuleSnapshotPreserved([object]$Before) {
    $after = Get-FirewallRuleSnapshot ([string]$Before.name)
    foreach ($property in $Before.PSObject.Properties.Name) {
        if ([string]$after.$property -ne [string]$Before.$property) {
            throw "PKG-9036: foreign firewall collision rule '$($Before.name)' changed property '$property'."
        }
    }
}

function New-ForeignFirewallCollision([string]$RuleId, [string]$DisplayName, [int]$Port) {
    New-NetFirewallRule `
        -Name $RuleId `
        -DisplayName $DisplayName `
        -Group 'Aegis QA foreign collision' `
        -Direction Inbound `
        -Action Block `
        -Enabled True `
        -Profile Private `
        -Protocol TCP `
        -LocalPort $Port `
        -Program (Join-Path $env:WINDIR 'System32\\cmd.exe') `
        -EdgeTraversalPolicy Block | Out-Null
    return Get-FirewallRuleSnapshot $RuleId
}

function Remove-QAFirewallCollision([string]$RuleId) {
    Get-NetFirewallRule -Name $RuleId -ErrorAction SilentlyContinue | Remove-NetFirewallRule
}

function Assert-NoBroadWriteAcl([string]$Path) {
    $broadSids = @('S-1-1-0', 'S-1-5-11', 'S-1-5-32-545')
    $writeRights = [Security.AccessControl.FileSystemRights]::Write -bor
        [Security.AccessControl.FileSystemRights]::Modify -bor
        [Security.AccessControl.FileSystemRights]::FullControl
    foreach ($entry in (Get-Acl -LiteralPath $Path).Access) {
        try {
            $sid = $entry.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        } catch {
            continue
        }
        if ($sid -in $broadSids -and $entry.AccessControlType -eq 'Allow' -and (($entry.FileSystemRights -band $writeRights) -ne 0)) {
            throw "SSH-9024: a broad principal has write access to an Aegis OpenSSH resource: $Path"
        }
    }
}

function Get-AegisOpenSshInspection {
    $payload = & $inspectScript
    if ($LASTEXITCODE -ne 0) {
        throw 'SSH-9016: the managed OpenSSH inspection failed.'
    }
    try {
        return ($payload | ConvertFrom-Json)
    } catch {
        throw "SSH-9016: the managed OpenSSH inspection returned invalid JSON: $($_.Exception.Message)"
    }
}

function Assert-Installed {
    foreach ($path in @($launcher, $uninstaller, $inspectScript, $enrollKeyScript, $removeKeyScript, $pairingFirewallScript)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "PKG-9014: installed artifact is missing: $path"
        }
    }
    foreach ($path in @($launcher, $uninstaller)) {
        $installedSignature = Get-AuthenticodeSignature -LiteralPath $path
        if ($installedSignature.Status -ne 'Valid' -or $installedSignature.SignerCertificate.Subject -ne $ExpectedSignerSubject) {
            throw "PKG-9006: installed artifact has an invalid or unexpected signer: $path"
        }
    }
    $service = Get-Service -Name 'AegisOpenSSH' -ErrorAction Stop
    if ($service.Status -ne 'Running' -or $service.StartType -ne 'Automatic') {
        throw 'SSH-9015: isolated AegisOpenSSH is not running with automatic startup.'
    }
    $inspection = Get-AegisOpenSshInspection
    if ($inspection.serviceName -ne 'AegisOpenSSH' -or $inspection.serviceStatus -ne 'Running' -or -not $inspection.firewallEnabled) {
        throw 'SSH-9016: the managed OpenSSH inspection did not confirm the expected service and firewall state.'
    }
    if ([string]::IsNullOrWhiteSpace([string]$inspection.hostKeyFingerprint) -or [int]$inspection.port -ne 48222) {
        throw 'SSH-9016: the managed OpenSSH inspection did not expose its pinned host key and private port.'
    }
    Assert-PrivateFirewallRule $pairingFirewallId $pairingFirewallName $launcher 48291
    Assert-PrivateFirewallRule $sshFirewallId $sshFirewallName ([string]$inspection.sshdPath) ([int]$inspection.port)
    foreach ($path in @($inspection.rootDirectory, $inspection.configPath, $inspection.authorizedKeysPath, $inspection.hostKeyPath, $inspection.manifestPath)) {
        Assert-NoBroadWriteAcl ([string]$path)
    }
    $inspection | ConvertTo-Json -Depth 8 | Out-File -LiteralPath (Join-Path $logRoot 'openssh-inspection.json') -Encoding utf8
    return $inspection
}

function Resolve-OpenSshClientExecutable([string]$Name) {
    $candidate = Join-Path $env:WINDIR "System32\OpenSSH\$Name"
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "SSH-9025: the official Windows OpenSSH client executable is unavailable: $Name"
    }
    return [IO.Path]::GetFullPath($candidate)
}

function Invoke-OpenSshClient(
    [string]$Executable,
    [string[]]$Arguments,
    [string]$EvidenceName
) {
    $output = (& $Executable @Arguments 2>&1 | Out-String)
    $exitCode = $LASTEXITCODE
    $output | Out-File -LiteralPath (Join-Path $qaRoot $EvidenceName) -Encoding utf8
    return [pscustomobject]@{
        exitCode = $exitCode
        output = $output
    }
}

function New-KnownHostsFile(
    [string]$Path,
    [int]$Port,
    [string]$PublicKeyPath
) {
    $publicKeyFields = (Get-Content -LiteralPath $PublicKeyPath -Raw).Trim() -split '\s+'
    if ($publicKeyFields.Count -lt 2) {
        throw 'SSH-9026: a QA public key could not be converted into a known_hosts record.'
    }
    $record = "[127.0.0.1]:$Port $($publicKeyFields[0]) $($publicKeyFields[1])"
    [IO.File]::WriteAllText($Path, "$record`n", [Text.UTF8Encoding]::new($false))
}

function Invoke-PackagedOpenSshProtocolSmoke([object]$Inspection) {
    if (Test-Path -LiteralPath $qaRoot) {
        Remove-Item -LiteralPath $qaRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Path $qaRoot -Force | Out-Null

    $ssh = Resolve-OpenSshClientExecutable 'ssh.exe'
    $sftp = Resolve-OpenSshClientExecutable 'sftp.exe'
    $sshKeygen = Resolve-OpenSshClientExecutable 'ssh-keygen.exe'
    $keyPath = Join-Path $qaRoot 'qa-client-ed25519'
    $knownHosts = Join-Path $qaRoot 'known_hosts'
    $wrongKnownHosts = Join-Path $qaRoot 'known_hosts-wrong'
    $emptyConfig = Join-Path $qaRoot 'empty-ssh-config'
    $sourcePath = Join-Path $qaRoot 'aegis sftp source.bin'
    $downloadPath = Join-Path $qaRoot 'aegis sftp downloaded.bin'
    $batchPath = Join-Path $qaRoot 'sftp-batch.txt'
    $remoteDirectory = ".aegis-qa-$([Guid]::NewGuid().ToString('N'))"
    $remoteFile = 'aegis sftp payload.bin'
    [IO.File]::WriteAllText($emptyConfig, '', [Text.UTF8Encoding]::new($false))

    $keygenResult = Invoke-OpenSshClient $sshKeygen @('-q', '-t', 'ed25519', '-N', '', '-f', $keyPath) 'ssh-keygen.txt'
    if ($keygenResult.exitCode -ne 0 -or -not (Test-Path -LiteralPath "$keyPath.pub" -PathType Leaf)) {
        throw 'SSH-9027: the QA client keypair could not be generated.'
    }

    $manifest = Get-Content -LiteralPath ([string]$Inspection.manifestPath) -Raw | ConvertFrom-Json
    New-KnownHostsFile $knownHosts ([int]$Inspection.port) ([string]$manifest.hostPublicKeyPath)
    New-KnownHostsFile $wrongKnownHosts ([int]$Inspection.port) "$keyPath.pub"

    $enrollmentPayload = & $enrollKeyScript -PublicKeyFile "$keyPath.pub" -DeviceId $qaDeviceId
    if ($LASTEXITCODE -ne 0) {
        throw 'SSH-9028: the packaged enrollment script rejected the QA public key.'
    }
    $enrollment = $enrollmentPayload | ConvertFrom-Json
    if ($enrollment.deviceId -ne $qaDeviceId -or [string]::IsNullOrWhiteSpace([string]$enrollment.fingerprint)) {
        throw 'SSH-9028: the packaged enrollment script returned an invalid result.'
    }

    $baseArguments = @(
        '-F', $emptyConfig,
        '-o', 'BatchMode=yes',
        '-o', 'IdentitiesOnly=yes',
        '-o', 'StrictHostKeyChecking=yes',
        '-o', 'GlobalKnownHostsFile=NUL',
        '-o', 'ConnectTimeout=10',
        '-i', $keyPath,
        '-P', [string]$Inspection.port,
        '-b', $batchPath,
        '-l', [string]$Inspection.authorizedUser,
        '127.0.0.1'
    )
    $sshArguments = @(
        '-F', $emptyConfig,
        '-o', 'BatchMode=yes',
        '-o', 'IdentitiesOnly=yes',
        '-o', 'StrictHostKeyChecking=yes',
        '-o', 'GlobalKnownHostsFile=NUL',
        '-o', 'ConnectTimeout=10',
        '-i', $keyPath,
        '-p', [string]$Inspection.port,
        '-l', [string]$Inspection.authorizedUser,
        '127.0.0.1'
    )

    try {
        $wrongPinArguments = @($sshArguments[0..9] + @('-o', "UserKnownHostsFile=$wrongKnownHosts") + $sshArguments[10..($sshArguments.Count - 1)] + @('cmd.exe /d /c echo AEGIS_WRONG_PIN'))
        $wrongPin = Invoke-OpenSshClient $ssh $wrongPinArguments 'ssh-wrong-host-pin.txt'
        if ($wrongPin.exitCode -eq 0) {
            throw 'SSH-9029: the OpenSSH client accepted an incorrect pinned host key.'
        }

        $pinnedArguments = @($sshArguments[0..9] + @('-o', "UserKnownHostsFile=$knownHosts") + $sshArguments[10..($sshArguments.Count - 1)] + @('cmd.exe /d /c echo AEGIS_SSH_SMOKE_OK'))
        $shell = Invoke-OpenSshClient $ssh $pinnedArguments 'ssh-shell.txt'
        if ($shell.exitCode -ne 0 -or $shell.output -notmatch 'AEGIS_SSH_SMOKE_OK') {
            throw 'SSH-9030: the packaged AegisOpenSSH shell smoke failed after host-key pinning and public-key authentication.'
        }

        [IO.File]::WriteAllBytes($sourcePath, [byte[]]::new(16 * 1024 * 1024))
        @(
            "mkdir `"$remoteDirectory`"",
            "put `"$sourcePath`" `"$remoteDirectory/$remoteFile`"",
            "ls -l `"$remoteDirectory`"",
            "get `"$remoteDirectory/$remoteFile`" `"$downloadPath`"",
            "rm `"$remoteDirectory/$remoteFile`"",
            "rmdir `"$remoteDirectory`""
        ) | Set-Content -LiteralPath $batchPath -Encoding ascii
        $sftpArguments = @($baseArguments[0..9] + @('-o', "UserKnownHostsFile=$knownHosts") + $baseArguments[10..($baseArguments.Count - 1)])
        $transfer = Invoke-OpenSshClient $sftp $sftpArguments 'sftp-transfer.txt'
        if ($transfer.exitCode -ne 0 -or -not (Test-Path -LiteralPath $downloadPath -PathType Leaf)) {
            throw 'SFT-9031: the packaged AegisOpenSSH list/upload/download smoke failed.'
        }
        if ((Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $downloadPath -Algorithm SHA256).Hash) {
            throw 'SFT-9032: the packaged SFTP download did not match the uploaded 16 MiB payload.'
        }

        $removalPayload = & $removeKeyScript -DeviceId $qaDeviceId
        if ($LASTEXITCODE -ne 0) {
            throw 'SSH-9033: the packaged key-removal script failed.'
        }
        $removal = $removalPayload | ConvertFrom-Json
        if (-not $removal.removed) {
            throw 'SSH-9033: the packaged key-removal script did not remove the QA credential.'
        }
        $rejected = Invoke-OpenSshClient $ssh $pinnedArguments 'ssh-after-key-removal.txt'
        if ($rejected.exitCode -eq 0) {
            throw 'SSH-9034: the removed QA SSH credential remained authorized.'
        }
    }
    finally {
        try { & $removeKeyScript -DeviceId $qaDeviceId | Out-Null } catch { }
    }
}

function Invoke-Uninstall {
    Stop-AegisLauncher
    $uninstallSignature = Get-AuthenticodeSignature -LiteralPath $uninstaller
    if ($uninstallSignature.Status -ne 'Valid' -or $uninstallSignature.SignerCertificate.Subject -ne $ExpectedSignerSubject) {
        throw 'PKG-9006: signed release produced an unsigned or unexpected uninstaller.'
    }
    $process = Start-Process -FilePath $uninstaller -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART') -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        throw "PKG-9017: uninstaller exited with code $($process.ExitCode)."
    }
    if (Get-Service -Name 'AegisOpenSSH' -ErrorAction SilentlyContinue) {
        throw 'SSH-9018: AegisOpenSSH service remained after uninstall.'
    }
    foreach ($ruleId in @($pairingFirewallId, $sshFirewallId)) {
        if (Get-NetFirewallRule -Name $ruleId -ErrorAction SilentlyContinue) {
            throw "PKG-9019: managed firewall rule remained after uninstall: $ruleId"
        }
    }
    if (Test-Path -LiteralPath $launcher -PathType Leaf) {
        throw 'PKG-9019: launcher remained after uninstall.'
    }
}

$stockOpenSshBefore = Get-StockOpenSshSnapshot
$foreignPairingBefore = $null
$foreignSshBefore = $null
try {
    $foreignPairingBefore = New-ForeignFirewallCollision $foreignPairingFirewallId $pairingFirewallName 48292
    $foreignSshBefore = New-ForeignFirewallCollision $foreignSshFirewallId $sshFirewallName 48223
    Invoke-Setup 'clean-install'
    $inspection = Assert-Installed
    Assert-FirewallRuleSnapshotPreserved $foreignPairingBefore
    Assert-FirewallRuleSnapshotPreserved $foreignSshBefore
    Start-AgentAndAwaitIdentity
    $identityHashBefore = (Get-FileHash -LiteralPath $identityFile -Algorithm SHA256).Hash
    Invoke-PackagedOpenSshProtocolSmoke $inspection

    & (Join-Path $PSScriptRoot '..\release\verify-windows-update.ps1') `
        -PackagePath $setup.FullName `
        -InstalledExecutable $launcher `
        -ExpectedSignerSubject $ExpectedSignerSubject `
        -AllowRepair | Out-File -LiteralPath (Join-Path $logRoot 'verified-update.txt') -Encoding utf8
    Invoke-Setup 'verified-repair-update'
    $inspectionAfterRepair = Assert-Installed
    Assert-FirewallRuleSnapshotPreserved $foreignPairingBefore
    Assert-FirewallRuleSnapshotPreserved $foreignSshBefore
    Start-AgentAndAwaitIdentity
    $identityHashAfter = (Get-FileHash -LiteralPath $identityFile -Algorithm SHA256).Hash
    if ($identityHashAfter -ne $identityHashBefore) {
        throw 'IDN-9020: verified update replaced the persistent Windows identity.'
    }
    Invoke-PackagedOpenSshProtocolSmoke $inspectionAfterRepair

    Invoke-Uninstall
    Assert-StockOpenSshPreserved $stockOpenSshBefore
    Assert-FirewallRuleSnapshotPreserved $foreignPairingBefore
    Assert-FirewallRuleSnapshotPreserved $foreignSshBefore
    if ($KeepInstalled) {
        Invoke-Setup 'reinstall-after-uninstall'
        $inspectionAfterReinstall = Assert-Installed
        Start-AgentAndAwaitIdentity
        if ((Get-FileHash -LiteralPath $identityFile -Algorithm SHA256).Hash -ne $identityHashBefore) {
            throw 'IDN-9020: reinstall replaced retained identity metadata.'
        }
        Invoke-PackagedOpenSshProtocolSmoke $inspectionAfterReinstall
    }
}
finally {
    Stop-AegisLauncher
    if ($foreignPairingBefore) { Remove-QAFirewallCollision $foreignPairingFirewallId }
    if ($foreignSshBefore) { Remove-QAFirewallCollision $foreignSshFirewallId }
}

Write-Host 'Windows clean install, verified repair update, packaged SSH/SFTP, identity retention, stock OpenSSH preservation, and uninstall smoke passed.' -ForegroundColor Green
