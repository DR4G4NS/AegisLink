[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 48222,

    [ValidatePattern('^[A-Za-z0-9_.@\\-]{1,128}$')]
    [string]$AuthorizedUser = '',

    [switch]$SkipCapabilityInstall
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$managedInstanceHelper = Join-Path $PSScriptRoot 'aegis-openssh-managed-instance.ps1'
if (-not (Test-Path -LiteralPath $managedInstanceHelper -PathType Leaf)) {
    throw 'SSH-7329 MANAGED_HELPER_MISSING: the managed-instance validator was not packaged with the provisioner.'
}
. $managedInstanceHelper

$serviceName = 'AegisOpenSSH'
$serviceDisplayName = 'Aegis OpenSSH Server'
$firewallRuleId = $script:AegisOpenSshFirewallRuleId
$firewallRuleName = $script:AegisOpenSshFirewallDisplayName
$firewallGroup = $script:AegisFirewallGroup
$layout = Get-AegisOpenSshLayout
$rootDirectory = $layout.rootDirectory
$manifestPath = $layout.manifestPath
$configPath = $layout.configPath
$authorizedKeysPath = $layout.authorizedKeysPath
$hostKeyPath = $layout.hostKeyPath
$hostPublicKeyPath = $layout.hostPublicKeyPath
$logPath = $layout.logPath
$capabilityMarkerPath = $layout.capabilityMarkerPath

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'SSH-7306 ADMINISTRATOR_REQUIRED: provisioning the isolated Aegis OpenSSH service requires elevation.'
    }
}

function Resolve-OpenSshExecutableSet {
    $candidateDirectories = @(
        (Join-Path $PSScriptRoot 'bin\OpenSSH-Win64'),
        (Join-Path $env:WINDIR 'System32\OpenSSH'),
        (Join-Path $env:ProgramFiles 'OpenSSH')
    )
    foreach ($candidateDirectory in $candidateDirectories) {
        $sshd = Join-Path $candidateDirectory 'sshd.exe'
        $sshKeygen = Join-Path $candidateDirectory 'ssh-keygen.exe'
        $sftpServer = Join-Path $candidateDirectory 'sftp-server.exe'
        if ((Test-Path -LiteralPath $sshd -PathType Leaf) -and
            (Test-Path -LiteralPath $sshKeygen -PathType Leaf) -and
            (Test-Path -LiteralPath $sftpServer -PathType Leaf)) {
            return [pscustomobject][ordered]@{
                sshdPath = [IO.Path]::GetFullPath($sshd)
                sshKeygenPath = [IO.Path]::GetFullPath($sshKeygen)
                sftpServerPath = [IO.Path]::GetFullPath($sftpServer)
            }
        }
    }
    return $null
}

function Install-OfficialOpenSshCapability {
    $capability = Get-WindowsCapability -Online -Name 'OpenSSH.Server*' |
        Sort-Object Name |
        Select-Object -First 1
    if (-not $capability) {
        throw 'SSH-7307 OPENSSH_CAPABILITY_UNAVAILABLE: Windows did not expose the official OpenSSH.Server capability.'
    }
    if ($capability.State -ne 'Installed') {
        Add-WindowsCapability -Online -Name $capability.Name | Out-Null
        return $true
    }
    return $false
}

function Convert-ToSshPath([string]$Path) {
    return ([IO.Path]::GetFullPath($Path) -replace '\\', '/')
}

function Invoke-External([string]$FilePath, [string[]]$Arguments, [string]$FailureCode) {
    & $FilePath @Arguments | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "${FailureCode} external command failed with exit code ${LASTEXITCODE}: $FilePath"
    }
}

function ConvertFrom-AegisIcaclsGrant {
    param([Parameter(Mandatory)][string]$Spec)
    $inheritance = [System.Security.AccessControl.InheritanceFlags]::None
    $rest = $Spec.Trim()
    while ($rest -match '^\((OI|CI|IO|NP)\)(.*)$') {
        switch ($Matches[1]) {
            'OI' { $inheritance = $inheritance -bor [System.Security.AccessControl.InheritanceFlags]::ObjectInherit }
            'CI' { $inheritance = $inheritance -bor [System.Security.AccessControl.InheritanceFlags]::ContainerInherit }
        }
        $rest = $Matches[2]
    }
    $tokens = @($rest.Trim().Trim('(', ')') -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $rights = $null
    if ($tokens -contains 'F') {
        $rights = [System.Security.AccessControl.FileSystemRights]::FullControl
    } elseif ($tokens -contains 'RX' -or (($tokens -contains 'R') -and ($tokens -contains 'X'))) {
        $rights = [System.Security.AccessControl.FileSystemRights]::ReadAndExecute
    } elseif (($tokens -contains 'R') -and ($tokens -contains 'W')) {
        $rights = [System.Security.AccessControl.FileSystemRights]::Read -bor [System.Security.AccessControl.FileSystemRights]::Write
    } elseif ($tokens -contains 'R') {
        $rights = [System.Security.AccessControl.FileSystemRights]::Read
    }
    if ($null -eq $rights) {
        throw "SSH-7310 ACL_CONFIGURATION_FAILED: unsupported icacls rights '$Spec'."
    }
    return [pscustomobject]@{
        Inheritance = $inheritance
        Rights = $rights
    }
}

function Set-AegisAcl {
    param(
        [Parameter(Mandatory)][string]$Path,
        [switch]$Directory,
        [string[]]$AdditionalGrants = @()
    )
    # Win32-OpenSSH refuses to start when host keys are owned by the desktop
    # user or remain readable by that account. Assign Administrators ownership
    # with takeown (sshd accepts BA/SY). Do not SetOwner(SYSTEM) in .NET; that
    # requires SeRestorePrivilege and raises ERROR_INVALID_OWNER (1307).
    $takeown = & "$env:WINDIR\System32\takeown.exe" /F $Path /A
    if ($LASTEXITCODE -ne 0) {
        throw "SSH-7310 ACL_CONFIGURATION_FAILED: takeown returned $LASTEXITCODE for $Path. $takeown"
    }
    $propagation = [System.Security.AccessControl.PropagationFlags]::None
    $allow = [System.Security.AccessControl.AccessControlType]::Allow
    $baseInheritance = [System.Security.AccessControl.InheritanceFlags]::None
    if ($Directory) {
        $baseInheritance = [System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
            [System.Security.AccessControl.InheritanceFlags]::ObjectInherit
    }
    $item = Get-Item -LiteralPath $Path -Force
    $acl = $item.GetAccessControl()
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($access in @($acl.Access)) {
        [void]$acl.RemoveAccessRule($access)
    }
    $full = [System.Security.AccessControl.FileSystemRights]::FullControl
    foreach ($sidValue in @('S-1-5-18', 'S-1-5-32-544')) {
        $sid = [Security.Principal.SecurityIdentifier]::new($sidValue)
        $rule = [System.Security.AccessControl.FileSystemAccessRule]::new($sid, $full, $baseInheritance, $propagation, $allow)
        [void]$acl.AddAccessRule($rule)
    }
    foreach ($grant in $AdditionalGrants) {
        if ($grant -notmatch '^\*(S-1-(?:\d+-){1,14}\d+):(.+)$') {
            throw "SSH-7310 ACL_CONFIGURATION_FAILED: unsupported additional grant '$grant'."
        }
        $sid = [Security.Principal.SecurityIdentifier]::new($Matches[1])
        $parsed = ConvertFrom-AegisIcaclsGrant -Spec $Matches[2]
        $grantInheritance = $baseInheritance -bor $parsed.Inheritance
        $rule = [System.Security.AccessControl.FileSystemAccessRule]::new(
            $sid,
            $parsed.Rights,
            $grantInheritance,
            $propagation,
            $allow)
        [void]$acl.AddAccessRule($rule)
    }
    try {
        $item.SetAccessControl($acl)
    } catch {
        throw "SSH-7310 ACL_CONFIGURATION_FAILED: could not apply ACLs on $Path. $($_.Exception.Message)"
    }
}

function Get-AegisService {
    return Get-CimInstance Win32_Service -Filter "Name='$serviceName'" -ErrorAction SilentlyContinue
}

function Get-AegisOpenSshImagePath {
    param(
        [Parameter(Mandatory)][string]$SshdPath,
        [Parameter(Mandatory)][string]$ConfigPath,
        [Parameter(Mandatory)][string]$LogPath
    )
    # Quote only the executable: Program Files contains spaces, and PowerShell 5.1
    # extra-quoting of sc.exe arguments otherwise yields ERROR_INVALID_PARAMETER (1639).
    return '"{0}" -f "{1}" -E "{2}"' -f $SshdPath, $ConfigPath, $LogPath
}

function New-AegisOpenSshService {
    param([Parameter(Mandatory)][string]$ImagePath)
    try {
        New-Service -Name $serviceName -BinaryPathName $ImagePath -DisplayName $serviceDisplayName -StartupType Automatic | Out-Null
    } catch {
        throw "SSH-7316 SERVICE_CREATE_FAILED: $($_.Exception.Message)"
    }
}

function Update-AegisOpenSshService {
    param([Parameter(Mandatory)][string]$ImagePath)
    $service = Get-AegisService
    if (-not $service) {
        throw 'SSH-7317 SERVICE_UPDATE_FAILED: the isolated service disappeared before its image path could be updated.'
    }
    $change = Invoke-CimMethod -InputObject $service -MethodName Change -Arguments @{
        PathName = $ImagePath
        DisplayName = $serviceDisplayName
        StartMode = 'Automatic'
    }
    if ([int]$change.ReturnValue -ne 0) {
        throw "SSH-7317 SERVICE_UPDATE_FAILED: Win32_Service.Change returned $($change.ReturnValue)."
    }
}

Assert-Administrator

$existingService = Get-AegisService
$manifestExists = Test-Path -LiteralPath $manifestPath -PathType Leaf
$managedManifest = $null
if ($manifestExists) {
    $managedManifest = Read-AegisOpenSshManifest -RequireBinaries -RequireInstanceFiles -RequireConfiguration
    Assert-AegisManagedServiceOwnership -Service $existingService -Manifest $managedManifest
} elseif ($existingService) {
    throw 'SSH-7329 MANAGED_MANIFEST_MISSING: refusing to adopt an existing root or service without its managed manifest.'
} elseif (Test-Path -LiteralPath $rootDirectory -PathType Container) {
    Assert-AegisPathNotReparsePoint -Path $rootDirectory -FailureName 'unmanaged root'
    $orphanedEntries = @(Get-ChildItem -Force -LiteralPath $rootDirectory -ErrorAction Stop)
    if ($orphanedEntries.Count -ne 0) {
        throw 'SSH-7329 MANAGED_MANIFEST_MISSING: refusing to adopt a non-empty root without its managed manifest.'
    }
    # A previous Setup rollback can leave only the fixed empty directory. It
    # contains no identity, keys, configuration, or user data, so removing it
    # is the one safe automatic recovery from a manifest-less root.
    Remove-Item -LiteralPath $rootDirectory -Force
}

if ($managedManifest) {
    if (-not $PSBoundParameters.ContainsKey('Port')) {
        $Port = [int]$managedManifest.port
    }
    if ([string]::IsNullOrWhiteSpace($AuthorizedUser)) {
        $accountIdentity = [pscustomobject][ordered]@{
            account = [string]$managedManifest.authorizedAccount
            sid = [string]$managedManifest.authorizedSid
        }
    } else {
        $requestedIdentity = Resolve-AegisWindowsAccount -Account $AuthorizedUser
        if (-not $requestedIdentity.sid.Equals([string]$managedManifest.authorizedSid, [StringComparison]::OrdinalIgnoreCase) -or
            -not $requestedIdentity.account.Equals([string]$managedManifest.authorizedAccount, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'SSH-7332 AUTHORIZED_ACCOUNT_CHANGE_REQUIRES_RESET: uninstall the isolated instance before binding it to another Windows account.'
        }
        $accountIdentity = $requestedIdentity
    }
    $sshdPath = [string]$managedManifest.sshdPath
    $sshKeygenPath = [string]$managedManifest.sshKeygenPath
    $sftpServerPath = [string]$managedManifest.sftpServerPath
} else {
    if ([string]::IsNullOrWhiteSpace($AuthorizedUser)) {
        # UAC can run with another administrator credential. Bind SSH to the
        # interactive desktop user's canonical account and stable SID.
        $interactiveUser = (Get-CimInstance Win32_ComputerSystem -ErrorAction SilentlyContinue).UserName
        if (-not [string]::IsNullOrWhiteSpace($interactiveUser)) {
            $AuthorizedUser = $interactiveUser.Trim()
        } else {
            $AuthorizedUser = (& "$env:WINDIR\System32\whoami.exe").Trim()
        }
    }
    $accountIdentity = Resolve-AegisWindowsAccount -Account $AuthorizedUser

    $binarySet = Resolve-OpenSshExecutableSet
    $capabilityInstalledByAegis = $false
    if (-not $binarySet) {
        if ($SkipCapabilityInstall) {
            throw 'SSH-7307 OPENSSH_SERVER_NOT_INSTALLED: official Windows OpenSSH Server is unavailable and capability installation was disabled.'
        }
        $capabilityInstalledByAegis = Install-OfficialOpenSshCapability
        $binarySet = Resolve-OpenSshExecutableSet
    }
    if (-not $binarySet) {
        throw 'SSH-7308 OPENSSH_BINARY_MISSING: sshd.exe, ssh-keygen.exe and sftp-server.exe are all required.'
    }
    $sshdPath = $binarySet.sshdPath
    $sshKeygenPath = $binarySet.sshKeygenPath
    $sftpServerPath = $binarySet.sftpServerPath
}

$sshdPath = Assert-AegisAllowedOpenSshBinary -Path $sshdPath -ExpectedName 'sshd.exe' -RequireExisting
$sshKeygenPath = Assert-AegisAllowedOpenSshBinary -Path $sshKeygenPath -ExpectedName 'ssh-keygen.exe' -RequireExisting
$sftpServerPath = Assert-AegisAllowedOpenSshBinary -Path $sftpServerPath -ExpectedName 'sftp-server.exe' -RequireExisting
Assert-AegisOpenSshBinarySet -SshdPath $sshdPath -SshKeygenPath $sshKeygenPath -SftpServerPath $sftpServerPath
$AuthorizedUser = $accountIdentity.account
$authorizedSid = $accountIdentity.sid

New-Item -ItemType Directory -Path $rootDirectory -Force | Out-Null
Assert-AegisPathNotReparsePoint -Path $rootDirectory -FailureName 'managed root'
Set-AegisAcl -Path $rootDirectory -Directory -AdditionalGrants @("*$($authorizedSid):(OI)(CI)RX")
if (-not $managedManifest -and $capabilityInstalledByAegis) {
    New-Item -ItemType File -Path $capabilityMarkerPath -Force | Out-Null
    Set-AegisAcl -Path $capabilityMarkerPath
}

if ($existingService -and $existingService.State -ne 'Stopped') {
    Stop-Service -Name $serviceName -Force
    (Get-Service -Name $serviceName).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(20))
}

$portOwner = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
if ($portOwner) {
    throw "SSH-7309 PRIVATE_PORT_IN_USE: TCP port $Port is already owned by process $($portOwner.OwningProcess)."
}

if (-not (Test-Path -LiteralPath $hostKeyPath -PathType Leaf)) {
    # Windows PowerShell 5.1 drops an empty native-process argument. Passing an
    # explicit pair of quotes preserves the empty passphrase expected by
    # ssh-keygen; otherwise -N consumes -f and reports "Too many arguments".
    Invoke-External -FilePath $sshKeygenPath -Arguments @('-q', '-t', 'ed25519', '-N', '""', '-f', $hostKeyPath) -FailureCode 'SSH-7314 HOST_KEY_GENERATION_FAILED:'
}
if (-not (Test-Path -LiteralPath $hostPublicKeyPath -PathType Leaf)) {
    $publicKey = (& $sshKeygenPath -y -f $hostKeyPath 2>$null)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($publicKey)) {
        throw 'SSH-7314 HOST_KEY_GENERATION_FAILED: the existing private host key could not regenerate its public key.'
    }
    [IO.File]::WriteAllText($hostPublicKeyPath, "$publicKey`n", [Text.UTF8Encoding]::new($false))
}
if (-not (Test-Path -LiteralPath $authorizedKeysPath -PathType Leaf)) {
    New-Item -ItemType File -Path $authorizedKeysPath -Force | Out-Null
}
Set-AegisAcl -Path $hostKeyPath
Set-AegisAcl -Path $hostPublicKeyPath -AdditionalGrants @("*$($authorizedSid):R")
Set-AegisAcl -Path $authorizedKeysPath -AdditionalGrants @("*$($authorizedSid):(R,W)")

$sshLoginName = Get-AegisSshAllowUsersName -Account $AuthorizedUser
$sshdConfig = @(
    '# Managed exclusively by Aegis Remote Control. Do not edit the system sshd_config from here.',
    "Port $Port",
    'AddressFamily any',
    'ListenAddress 127.0.0.1',
    "HostKey $(Convert-ToSshPath $hostKeyPath)",
    "AuthorizedKeysFile $(Convert-ToSshPath $authorizedKeysPath)",
    "AllowUsers $sshLoginName",
    'AuthenticationMethods publickey',
    'PubkeyAuthentication yes',
    'PasswordAuthentication no',
    'KbdInteractiveAuthentication no',
    'PermitEmptyPasswords no',
    'PermitRootLogin no',
    'AllowAgentForwarding no',
    'AllowTcpForwarding no',
    'GatewayPorts no',
    'X11Forwarding no',
    'PermitTunnel no',
    'MaxAuthTries 6',
    'LoginGraceTime 30',
    'ClientAliveInterval 30',
    'ClientAliveCountMax 3',
    'LogLevel VERBOSE',
    "PidFile $(Convert-ToSshPath (Join-Path $rootDirectory 'sshd.pid'))",
    "Subsystem sftp `"$(Convert-ToSshPath $sftpServerPath)`""
)
$temporaryConfig = "$configPath.$PID.$([Guid]::NewGuid().ToString('N')).tmp"
try {
    [IO.File]::WriteAllLines($temporaryConfig, $sshdConfig, [Text.UTF8Encoding]::new($false))
    & $sshdPath -t -f $temporaryConfig 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "SSH-7315 CONFIG_VALIDATION_FAILED: sshd -t rejected the staged configuration with exit code $LASTEXITCODE."
    }
    Move-Item -LiteralPath $temporaryConfig -Destination $configPath -Force
} finally {
    if (Test-Path -LiteralPath $temporaryConfig) {
        Remove-Item -LiteralPath $temporaryConfig -Force
    }
}
Set-AegisAcl -Path $configPath -AdditionalGrants @("*$($authorizedSid):R")

$configSha256 = Get-AegisFileSha256 -Path $configPath
$hostPublicKeySha256 = Get-AegisFileSha256 -Path $hostPublicKeyPath
$instanceId = if ($managedManifest) { [string]$managedManifest.instanceId } else { [Guid]::NewGuid().ToString('D') }
$createdAtUtc = if ($managedManifest) { [string]$managedManifest.createdAtUtc } else { [DateTimeOffset]::UtcNow.ToString('O') }
$newManifest = [pscustomobject][ordered]@{
    schemaVersion = $script:AegisOpenSshManifestSchemaVersion
    instanceId = $instanceId
    serviceName = $serviceName
    rootDirectory = $rootDirectory
    configPath = $configPath
    authorizedKeysPath = $authorizedKeysPath
    hostKeyPath = $hostKeyPath
    hostPublicKeyPath = $hostPublicKeyPath
    logPath = $logPath
    sshdPath = $sshdPath
    sshKeygenPath = $sshKeygenPath
    sftpServerPath = $sftpServerPath
    authorizedAccount = $AuthorizedUser
    authorizedSid = $authorizedSid
    port = $Port
    appScopedAccess = $true
    firewallRuleId = $firewallRuleId
    firewallDisplayName = $firewallRuleName
    firewallGroup = $firewallGroup
    configSha256 = $configSha256
    hostPublicKeySha256 = $hostPublicKeySha256
    createdAtUtc = $createdAtUtc
}
Write-AegisOpenSshManifest -Manifest $newManifest
Set-AegisAcl -Path $manifestPath -AdditionalGrants @("*$($authorizedSid):R")
$newManifest = Read-AegisOpenSshManifest -RequireBinaries -RequireInstanceFiles -RequireConfiguration

$binaryPath = Get-AegisOpenSshImagePath -SshdPath $sshdPath -ConfigPath $configPath -LogPath $logPath
if (-not $existingService) {
    New-AegisOpenSshService -ImagePath $binaryPath
} else {
    Update-AegisOpenSshService -ImagePath $binaryPath
}
& "$env:WINDIR\System32\sc.exe" description $serviceName 'Isolated OpenSSH Server instance managed by Aegis Remote Control.' | Out-Null

$service = Get-AegisService
Assert-AegisManagedServiceOwnership -Service $service -Manifest $newManifest
Install-AegisManagedFirewallRule `
    -RuleId $firewallRuleId `
    -DisplayName $firewallRuleName `
    -Group $firewallGroup `
    -Program $sshdPath `
    -Port $Port

try {
    Start-Service -Name $serviceName -ErrorAction Stop
    (Get-Service -Name $serviceName).WaitForStatus('Running', [TimeSpan]::FromSeconds(20))
} catch {
    $sshdLogTail = ''
    if (Test-Path -LiteralPath $logPath) {
        $sshdLogTail = ((Get-Content -LiteralPath $logPath -ErrorAction SilentlyContinue | Select-Object -Last 12) -join ' | ')
    }
    throw "SSH-7319 SERVICE_START_FAILED: $($_.Exception.Message) sshd.log: $sshdLogTail"
}
$service = Get-AegisService
$hostKeyFingerprint = Get-AegisHostKeyFingerprint -SshKeygenPath $sshKeygenPath -HostPublicKeyPath $hostPublicKeyPath
# Wake-on-LAN remains observational: provisioning never changes adapter driver power settings.
$wakeOnLanConfigs = @()
$inspectionScript = Join-Path $PSScriptRoot 'inspect-aegis-openssh.ps1'
if (-not (Test-Path -LiteralPath $inspectionScript -PathType Leaf)) {
    throw 'SSH-7329 MANAGED_INSPECTOR_MISSING: inspect-aegis-openssh.ps1 was not packaged with the provisioner.'
}
try {
    $inspection = (& $inspectionScript | ConvertFrom-Json)
    $wakeOnLanConfigs = @($inspection.wakeOnLanConfigs)
} catch {
    throw "SSH-7330 POST_PROVISION_INSPECTION_FAILED: $($_.Exception.Message)"
}

[ordered]@{
    serviceName = $serviceName
    serviceStatus = $service.State
    appScopedAccess = $true
    port = $Port
    rootDirectory = $rootDirectory
    configPath = $configPath
    authorizedKeysPath = $authorizedKeysPath
    hostKeyPath = $hostKeyPath
    hostKeyFingerprint = $hostKeyFingerprint
    hostKeyAlgorithm = 'ssh-ed25519'
    firewallRuleId = $firewallRuleId
    firewallRuleName = $firewallRuleName
    sshdPath = $sshdPath
    authorizedUser = $sshLoginName
    authorizedUserSid = $authorizedSid
    manifestPath = $manifestPath
    manifestSchemaVersion = $script:AegisOpenSshManifestSchemaVersion
    instanceId = $instanceId
    firewallEnabled = $true
    wakeOnLanConfigs = $wakeOnLanConfigs
} | ConvertTo-Json -Compress
