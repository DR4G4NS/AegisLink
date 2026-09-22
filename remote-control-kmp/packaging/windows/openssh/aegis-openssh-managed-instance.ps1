$script:AegisOpenSshManifestSchemaVersion = 3
$script:AegisOpenSshServiceName = 'AegisOpenSSH'
$script:AegisOpenSshFirewallRuleId = 'Aegis.OpenSSH.Firewall.v1'
$script:AegisOpenSshFirewallDisplayName = 'Aegis OpenSSH (Private and Domain)'
$script:AegisFirewallGroup = 'Aegis Remote Control'
$script:AegisOpenSshManifestProperties = @(
    'schemaVersion',
    'instanceId',
    'serviceName',
    'rootDirectory',
    'configPath',
    'authorizedKeysPath',
    'hostKeyPath',
    'hostPublicKeyPath',
    'logPath',
    'sshdPath',
    'sshKeygenPath',
    'sftpServerPath',
    'authorizedAccount',
    'authorizedSid',
    'port',
    'appScopedAccess',
    'firewallRuleId',
    'firewallDisplayName',
    'firewallGroup',
    'configSha256',
    'hostPublicKeySha256',
    'createdAtUtc'
)

function Get-AegisOpenSshLayout {
    if ([string]::IsNullOrWhiteSpace($env:ProgramData)) {
        throw 'SSH-7329 MANAGED_MANIFEST_INVALID: ProgramData is unavailable.'
    }
    $rootDirectory = [IO.Path]::GetFullPath((Join-Path $env:ProgramData 'Aegis\OpenSSH'))
    $hostKeyPath = Join-Path $rootDirectory 'ssh_host_ed25519_key'
    return [pscustomobject][ordered]@{
        rootDirectory = $rootDirectory
        manifestPath = Join-Path $rootDirectory 'managed-instance.json'
        configPath = Join-Path $rootDirectory 'sshd_config'
        authorizedKeysPath = Join-Path $rootDirectory 'authorized_keys'
        hostKeyPath = $hostKeyPath
        hostPublicKeyPath = "$hostKeyPath.pub"
        logPath = Join-Path $rootDirectory 'sshd.log'
        capabilityMarkerPath = Join-Path $rootDirectory 'openssh-capability-installed-by-aegis'
    }
}

function Test-AegisPathEqual {
    param(
        [Parameter(Mandatory)][string]$Left,
        [Parameter(Mandatory)][string]$Right
    )
    try {
        $leftPath = [IO.Path]::GetFullPath($Left).TrimEnd('\', '/')
        $rightPath = [IO.Path]::GetFullPath($Right).TrimEnd('\', '/')
        return $leftPath.Equals($rightPath, [StringComparison]::OrdinalIgnoreCase)
    } catch {
        return $false
    }
}

function Assert-AegisPathNotReparsePoint {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$FailureName
    )
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "SSH-7330 MANAGED_PATH_REPARSE_POINT: $FailureName is a reparse point: $Path"
    }
}

function Assert-AegisManagedPathChain {
    param([Parameter(Mandatory)][string]$Path)

    if ([string]::IsNullOrWhiteSpace($env:ProgramData)) {
        throw 'SSH-7329 MANAGED_MANIFEST_INVALID: ProgramData is unavailable.'
    }
    $programDataRoot = [IO.Path]::GetFullPath($env:ProgramData).TrimEnd('\', '/')
    $managedPath = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    $prefix = $programDataRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $managedPath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "SSH-7330 MANAGED_PATH_INVALID: the managed path escaped ProgramData: $managedPath"
    }

    $relativePath = $managedPath.Substring($prefix.Length)
    $currentPath = $programDataRoot
    foreach ($segment in $relativePath.Split(@('\', '/'), [StringSplitOptions]::RemoveEmptyEntries)) {
        $currentPath = Join-Path $currentPath $segment
        if (Test-Path -LiteralPath $currentPath) {
            Assert-AegisPathNotReparsePoint -Path $currentPath -FailureName "managed path component '$segment'"
        }
    }
}

function Get-AegisFileSha256 {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "SSH-7330 MANAGED_FILE_MISSING: required managed file is unavailable: $Path"
    }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256 -ErrorAction Stop).Hash.ToUpperInvariant()
}

function Get-AegisHostKeyFingerprint {
    param(
        [Parameter(Mandatory)][string]$SshKeygenPath,
        [Parameter(Mandatory)][string]$HostPublicKeyPath
    )
    # Splatting keeps -E from being stolen as -ErrorAction. Do not pipe native
    # output before reading $LASTEXITCODE; PowerShell 5.1 drops the exit code.
    $previousEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = @(& $SshKeygenPath @('-lf', $HostPublicKeyPath, '-E', 'sha256'))
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousEap
    }
    $line = [string]($lines | Select-Object -First 1)
    if ($exitCode -ne 0 -or $line -notmatch '(SHA256:[A-Za-z0-9+/]{43,44})') {
        throw "SSH-7318 HOST_KEY_FINGERPRINT_FAILED: ssh-keygen exit=$exitCode output='$line'"
    }
    return [string]$Matches[1]
}

function Resolve-AegisWindowsAccount {
    param([Parameter(Mandatory)][string]$Account)
    if ([string]::IsNullOrWhiteSpace($Account) -or $Account -notmatch '^[A-Za-z0-9_.@\\-]{1,128}$') {
        throw 'SSH-7312 AUTHORIZED_USER_INVALID: the Windows account cannot be represented safely in AllowUsers.'
    }
    try {
        $requestedAccount = [Security.Principal.NTAccount]::new($Account)
        $sid = $requestedAccount.Translate([Security.Principal.SecurityIdentifier])
        $canonicalAccount = $sid.Translate([Security.Principal.NTAccount]).Value.ToLowerInvariant()
    } catch {
        throw "SSH-7331 AUTHORIZED_ACCOUNT_UNRESOLVED: Windows could not resolve the requested account to a stable SID: $Account"
    }
    if ($canonicalAccount -notmatch '^[A-Za-z0-9_.@\\-]{1,128}$') {
        throw 'SSH-7312 AUTHORIZED_USER_INVALID: the canonical Windows account cannot be represented safely in AllowUsers.'
    }
    return [pscustomobject][ordered]@{
        account = $canonicalAccount
        sid = $sid.Value
    }
}

function Get-AegisSshAllowUsersName {
    param([Parameter(Mandatory)][string]$Account)
    $trimmed = $Account.Trim()
    if ([string]::IsNullOrWhiteSpace($trimmed)) {
        throw 'SSH-7312 AUTHORIZED_USER_INVALID: the Windows account cannot be represented safely in AllowUsers.'
    }
    # Win32-OpenSSH canonicalizes DOMAIN\user and user@DOMAIN to the SAM
    # account before AllowUsers. Listing DOMAIN\user therefore matches nobody,
    # including clients that log in as DOMAIN\user.
    $loginName = if ($trimmed.Contains('\')) {
        $trimmed.Split('\')[-1]
    } elseif ($trimmed.Contains('@')) {
        $trimmed.Split('@')[0]
    } else {
        $trimmed
    }
    if ([string]::IsNullOrWhiteSpace($loginName) -or $loginName -notmatch '^[A-Za-z0-9._-]{1,64}$') {
        throw "SSH-7312 AUTHORIZED_USER_INVALID: OpenSSH AllowUsers cannot use '$trimmed'."
    }
    return $loginName
}

function Assert-AegisAllowedOpenSshBinary {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$ExpectedName,
        [switch]$RequireExisting
    )
    try {
        $fullPath = [IO.Path]::GetFullPath($Path)
    } catch {
        throw "SSH-7330 MANAGED_BINARY_PATH_INVALID: $ExpectedName has an invalid absolute path."
    }
    if (-not [IO.Path]::GetFileName($fullPath).Equals($ExpectedName, [StringComparison]::OrdinalIgnoreCase)) {
        throw "SSH-7330 MANAGED_BINARY_PATH_INVALID: the persisted binary is not $ExpectedName."
    }
    $allowedRoots = @([IO.Path]::GetFullPath((Join-Path $env:WINDIR 'System32\OpenSSH')))
    $bundledRoot = Join-Path $PSScriptRoot 'bin\OpenSSH-Win64'
    if (Test-Path -LiteralPath $bundledRoot -PathType Container) {
        $allowedRoots += [IO.Path]::GetFullPath($bundledRoot)
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ProgramFiles)) {
        $allowedRoots += [IO.Path]::GetFullPath((Join-Path $env:ProgramFiles 'OpenSSH'))
    }
    $isAllowed = $false
    foreach ($allowedRoot in $allowedRoots) {
        $prefix = $allowedRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
        if ($fullPath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
            $isAllowed = $true
            break
        }
    }
    if (-not $isAllowed) {
        throw "SSH-7330 MANAGED_BINARY_PATH_INVALID: $ExpectedName is outside the supported Windows OpenSSH locations."
    }
    if ($RequireExisting) {
        if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
            throw "SSH-7330 MANAGED_BINARY_MISSING: the persisted $ExpectedName path no longer exists: $fullPath"
        }
        Assert-AegisPathNotReparsePoint -Path $fullPath -FailureName $ExpectedName
    }
    return $fullPath
}

function Assert-AegisOpenSshBinarySet {
    param(
        [Parameter(Mandatory)][string]$SshdPath,
        [Parameter(Mandatory)][string]$SshKeygenPath,
        [Parameter(Mandatory)][string]$SftpServerPath
    )
    $directories = @(
        [IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($SshdPath)),
        [IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($SshKeygenPath)),
        [IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($SftpServerPath))
    )
    if (-not (Test-AegisPathEqual $directories[0] $directories[1]) -or
        -not (Test-AegisPathEqual $directories[0] $directories[2])) {
        throw 'SSH-7330 MANAGED_BINARY_SET_MISMATCH: all persisted OpenSSH executables must come from the same installation directory.'
    }
}

function Get-AegisSingleConfigDirective {
    param(
        [Parameter(Mandatory)][string[]]$Lines,
        [Parameter(Mandatory)][string]$Name
    )
    $pattern = '^\s*' + [regex]::Escape($Name) + '\s+(.+?)\s*$'
    $values = @(
        $Lines |
            Where-Object { $_ -match $pattern } |
            ForEach-Object { $Matches[1] }
    )
    if ($values.Count -ne 1) {
        throw "SSH-7330 MANAGED_CONFIG_INVALID: expected exactly one $Name directive."
    }
    return [string]$values[0]
}

function ConvertFrom-AegisSshPath {
    param([Parameter(Mandatory)][string]$Path)
    $candidate = $Path.Trim()
    if ($candidate.Length -ge 2 -and $candidate[0] -eq '"' -and $candidate[$candidate.Length - 1] -eq '"') {
        $candidate = $candidate.Substring(1, $candidate.Length - 2)
    }
    return [IO.Path]::GetFullPath(($candidate -replace '/', '\'))
}

function Assert-AegisManagedConfiguration {
    param([Parameter(Mandatory)][object]$Manifest)
    if (-not (Test-Path -LiteralPath $Manifest.configPath -PathType Leaf)) {
        throw "SSH-7330 MANAGED_CONFIG_MISSING: the persisted configuration is unavailable: $($Manifest.configPath)"
    }
    Assert-AegisPathNotReparsePoint -Path $Manifest.configPath -FailureName 'sshd_config'
    $actualHash = Get-AegisFileSha256 -Path $Manifest.configPath
    if (-not $actualHash.Equals([string]$Manifest.configSha256, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'SSH-7330 MANAGED_CONFIG_HASH_MISMATCH: sshd_config changed outside the managed installer.'
    }

    $lines = @(Get-Content -LiteralPath $Manifest.configPath -ErrorAction Stop)
    if ($lines | Where-Object { $_ -match '^\s*(Match|Include)\b' } | Select-Object -First 1) {
        throw 'SSH-7330 MANAGED_CONFIG_INVALID: Match and Include directives are not permitted in the isolated configuration.'
    }
    $portValue = Get-AegisSingleConfigDirective -Lines $lines -Name 'Port'
    if ($Manifest.PSObject.Properties.Name -contains 'appScopedAccess' -and $Manifest.appScopedAccess) {
        $listenAddress = Get-AegisSingleConfigDirective -Lines $lines -Name 'ListenAddress'
        if ($listenAddress -ne '127.0.0.1') {
            throw 'SSH-7334 MANAGED_LISTENER_NOT_LOCAL: app-scoped access requires a loopback-only SSH service.'
        }
    }
    if ($portValue -notmatch '^\d+$' -or [int]$portValue -ne [int]$Manifest.port) {
        throw 'SSH-7330 MANAGED_CONFIG_INVALID: Port does not match the managed manifest.'
    }
    $allowUsers = Get-AegisSingleConfigDirective -Lines $lines -Name 'AllowUsers'
    $expectedAllowUsers = Get-AegisSshAllowUsersName -Account ([string]$Manifest.authorizedAccount)
    $legacyAllowUsers = [string]$Manifest.authorizedAccount
    if (
        -not $allowUsers.Equals($expectedAllowUsers, [StringComparison]::OrdinalIgnoreCase) -and
        -not $allowUsers.Equals($legacyAllowUsers, [StringComparison]::OrdinalIgnoreCase)
    ) {
        throw 'SSH-7330 MANAGED_CONFIG_INVALID: AllowUsers does not match the SID-bound managed account or its SAM login name.'
    }
    $hostKey = ConvertFrom-AegisSshPath (Get-AegisSingleConfigDirective -Lines $lines -Name 'HostKey')
    if (-not (Test-AegisPathEqual $hostKey ([string]$Manifest.hostKeyPath))) {
        throw 'SSH-7330 MANAGED_CONFIG_INVALID: HostKey does not match the managed manifest.'
    }
    $authorizedKeys = ConvertFrom-AegisSshPath (Get-AegisSingleConfigDirective -Lines $lines -Name 'AuthorizedKeysFile')
    if (-not (Test-AegisPathEqual $authorizedKeys ([string]$Manifest.authorizedKeysPath))) {
        throw 'SSH-7330 MANAGED_CONFIG_INVALID: AuthorizedKeysFile does not match the managed manifest.'
    }
    $subsystem = Get-AegisSingleConfigDirective -Lines $lines -Name 'Subsystem'
    if (-not $subsystem.StartsWith('sftp ', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'SSH-7330 MANAGED_CONFIG_INVALID: the managed SFTP subsystem must begin with sftp.'
    }
    $subsystemPath = ConvertFrom-AegisSshPath ($subsystem.Substring(5).Trim())
    if (-not (Test-AegisPathEqual $subsystemPath ([string]$Manifest.sftpServerPath))) {
        throw 'SSH-7330 MANAGED_CONFIG_INVALID: the managed SFTP subsystem must reference the bundled sftp-server.exe.'
    }

    $requiredValues = [ordered]@{
        AuthenticationMethods = 'publickey'
        PubkeyAuthentication = 'yes'
        PasswordAuthentication = 'no'
        KbdInteractiveAuthentication = 'no'
        PermitEmptyPasswords = 'no'
        PermitRootLogin = 'no'
        AllowAgentForwarding = 'no'
        AllowTcpForwarding = 'no'
        GatewayPorts = 'no'
        X11Forwarding = 'no'
        PermitTunnel = 'no'
    }
    foreach ($entry in $requiredValues.GetEnumerator()) {
        $actual = Get-AegisSingleConfigDirective -Lines $lines -Name ([string]$entry.Key)
        if (-not $actual.Equals([string]$entry.Value, [StringComparison]::OrdinalIgnoreCase)) {
            throw "SSH-7330 MANAGED_CONFIG_INVALID: $($entry.Key) must remain $($entry.Value)."
        }
    }
}

function Read-AegisOpenSshManifest {
    param(
        [switch]$RequireBinaries,
        [switch]$RequireInstanceFiles,
        [switch]$RequireConfiguration
    )
    $layout = Get-AegisOpenSshLayout
    if (-not (Test-Path -LiteralPath $layout.manifestPath -PathType Leaf)) {
        throw "SSH-7329 MANAGED_MANIFEST_MISSING: the isolated instance has no managed manifest at $($layout.manifestPath)."
    }
    Assert-AegisPathNotReparsePoint -Path $layout.rootDirectory -FailureName 'managed root'
    Assert-AegisPathNotReparsePoint -Path $layout.manifestPath -FailureName 'managed manifest'
    $manifestItem = Get-Item -LiteralPath $layout.manifestPath -Force -ErrorAction Stop
    if ($manifestItem.Length -le 0 -or $manifestItem.Length -gt 65536) {
        throw 'SSH-7329 MANAGED_MANIFEST_INVALID: managed-instance.json has an invalid size.'
    }
    try {
        $raw = Get-Content -LiteralPath $layout.manifestPath -Raw -ErrorAction Stop
        $manifest = $raw | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw 'SSH-7329 MANAGED_MANIFEST_INVALID: managed-instance.json is not valid JSON.'
    }
    $actualProperties = @($manifest.PSObject.Properties | ForEach-Object { $_.Name })
    $expectedProperties = @($script:AegisOpenSshManifestProperties | Where-Object { $_ -ne 'appScopedAccess' -or [int]$manifest.schemaVersion -ge 3 })
    if ($actualProperties.Count -ne $expectedProperties.Count) {
        throw 'SSH-7329 MANAGED_MANIFEST_INVALID: managed-instance.json has an unexpected property set.'
    }
    foreach ($propertyName in $expectedProperties) {
        if (-not ($actualProperties -ccontains $propertyName)) {
            throw "SSH-7329 MANAGED_MANIFEST_INVALID: required property is missing: $propertyName"
        }
        $occurrences = [regex]::Matches($raw, ('"' + [regex]::Escape($propertyName) + '"\s*:')).Count
        if ($occurrences -ne 1) {
            throw "SSH-7329 MANAGED_MANIFEST_INVALID: property must occur exactly once: $propertyName"
        }
    }
    if ([int]$manifest.schemaVersion -notin @(2, $script:AegisOpenSshManifestSchemaVersion)) {
        throw 'SSH-7329 MANAGED_MANIFEST_SCHEMA_UNSUPPORTED: the managed manifest schema is not supported.'
    }
    if ([int]$manifest.schemaVersion -ge 3 -and ($manifest.appScopedAccess -isnot [bool] -or -not $manifest.appScopedAccess)) {
        throw 'SSH-7334 MANAGED_LISTENER_NOT_LOCAL: app-scoped access must be enabled.'
    }
    $instanceId = [Guid]::Empty
    if (-not [Guid]::TryParseExact([string]$manifest.instanceId, 'D', [ref]$instanceId) -or $instanceId -eq [Guid]::Empty) {
        throw 'SSH-7329 MANAGED_MANIFEST_INVALID: instanceId is not a canonical non-empty GUID.'
    }
    if (-not ([string]$manifest.serviceName).Equals($script:AegisOpenSshServiceName, [StringComparison]::Ordinal)) {
        throw 'SSH-7329 MANAGED_MANIFEST_INVALID: serviceName is not the isolated Aegis service.'
    }
    if ([int]$manifest.port -lt 1024 -or [int]$manifest.port -gt 65535) {
        throw 'SSH-7329 MANAGED_MANIFEST_INVALID: port is outside the private service range.'
    }
    if (-not ([string]$manifest.firewallRuleId).Equals($script:AegisOpenSshFirewallRuleId, [StringComparison]::Ordinal) -or
        -not ([string]$manifest.firewallDisplayName).Equals($script:AegisOpenSshFirewallDisplayName, [StringComparison]::Ordinal) -or
        -not ([string]$manifest.firewallGroup).Equals($script:AegisFirewallGroup, [StringComparison]::Ordinal)) {
        throw 'SSH-7329 MANAGED_MANIFEST_INVALID: firewall ownership metadata does not match the isolated Aegis rule.'
    }
    $createdAt = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse([string]$manifest.createdAtUtc, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind, [ref]$createdAt)) {
        throw 'SSH-7329 MANAGED_MANIFEST_INVALID: createdAtUtc is invalid.'
    }
    foreach ($hashName in @('configSha256', 'hostPublicKeySha256')) {
        if ([string]$manifest.$hashName -notmatch '^[A-Fa-f0-9]{64}$') {
            throw "SSH-7329 MANAGED_MANIFEST_INVALID: $hashName is not a SHA-256 digest."
        }
    }

    $expectedPaths = [ordered]@{
        rootDirectory = $layout.rootDirectory
        configPath = $layout.configPath
        authorizedKeysPath = $layout.authorizedKeysPath
        hostKeyPath = $layout.hostKeyPath
        hostPublicKeyPath = $layout.hostPublicKeyPath
        logPath = $layout.logPath
    }
    foreach ($entry in $expectedPaths.GetEnumerator()) {
        if (-not (Test-AegisPathEqual ([string]$manifest.($entry.Key)) ([string]$entry.Value))) {
            throw "SSH-7329 MANAGED_MANIFEST_PATH_MISMATCH: $($entry.Key) escaped the fixed Aegis managed root."
        }
    }
    $manifest.sshdPath = Assert-AegisAllowedOpenSshBinary -Path ([string]$manifest.sshdPath) -ExpectedName 'sshd.exe' -RequireExisting:$RequireBinaries
    $manifest.sshKeygenPath = Assert-AegisAllowedOpenSshBinary -Path ([string]$manifest.sshKeygenPath) -ExpectedName 'ssh-keygen.exe' -RequireExisting:$RequireBinaries
    $manifest.sftpServerPath = Assert-AegisAllowedOpenSshBinary -Path ([string]$manifest.sftpServerPath) -ExpectedName 'sftp-server.exe' -RequireExisting:$RequireBinaries
    Assert-AegisOpenSshBinarySet -SshdPath $manifest.sshdPath -SshKeygenPath $manifest.sshKeygenPath -SftpServerPath $manifest.sftpServerPath

    if ([string]$manifest.authorizedSid -notmatch '^S-1-(?:\d+-){1,14}\d+$') {
        throw 'SSH-7331 AUTHORIZED_ACCOUNT_BINDING_INVALID: the managed SID is malformed.'
    }
    try {
        $sid = [Security.Principal.SecurityIdentifier]::new([string]$manifest.authorizedSid)
        $currentAccount = $sid.Translate([Security.Principal.NTAccount]).Value.ToLowerInvariant()
    } catch {
        throw 'SSH-7331 AUTHORIZED_ACCOUNT_BINDING_INVALID: the managed SID no longer resolves to a Windows account.'
    }
    if (-not $currentAccount.Equals([string]$manifest.authorizedAccount, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'SSH-7331 AUTHORIZED_ACCOUNT_BINDING_INVALID: the persisted account name no longer resolves to the persisted SID.'
    }

    if ($RequireInstanceFiles) {
        # The desktop user must never receive read access to the private host
        # key. Elevated install/uninstall validates it; unprivileged inspect
        # validates the public half and the exact service/config binding.
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
        $principal = [Security.Principal.WindowsPrincipal]::new($identity)
        $isAdministrator = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
        $requiredPaths = @($manifest.authorizedKeysPath, $manifest.hostPublicKeyPath)
        if ($isAdministrator) { $requiredPaths += $manifest.hostKeyPath }
        foreach ($requiredPath in $requiredPaths) {
            if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
                throw "SSH-7330 MANAGED_FILE_MISSING: required isolated instance file is unavailable: $requiredPath"
            }
            Assert-AegisPathNotReparsePoint -Path $requiredPath -FailureName ([IO.Path]::GetFileName($requiredPath))
        }
        $hostPublicKeyHash = Get-AegisFileSha256 -Path $manifest.hostPublicKeyPath
        if (-not $hostPublicKeyHash.Equals([string]$manifest.hostPublicKeySha256, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'SSH-7330 HOST_PUBLIC_KEY_HASH_MISMATCH: the persisted host public key changed outside the managed installer.'
        }
    }
    if ($RequireConfiguration) {
        Assert-AegisManagedConfiguration -Manifest $manifest
    }
    return $manifest
}

function Write-AegisOpenSshManifest {
    param([Parameter(Mandatory)][object]$Manifest)
    $layout = Get-AegisOpenSshLayout
    $json = ConvertTo-Json -InputObject $Manifest -Compress -Depth 4
    $temporaryPath = "$($layout.manifestPath).$PID.$([Guid]::NewGuid().ToString('N')).tmp"
    try {
        [IO.File]::WriteAllText($temporaryPath, $json, [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $temporaryPath -Destination $layout.manifestPath -Force
    } finally {
        if (Test-Path -LiteralPath $temporaryPath) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
    }
}

function Assert-AegisManagedServiceOwnership {
    param(
        [AllowNull()][object]$Service,
        [Parameter(Mandatory)][object]$Manifest
    )
    if (-not $Service) { return }
    if (-not ([string]$Service.Name).Equals([string]$Manifest.serviceName, [StringComparison]::Ordinal)) {
        throw 'SSH-7311 SERVICE_NAME_COLLISION: the discovered service name is not owned by the managed manifest.'
    }
    $commandLine = [string]$Service.PathName
    $pattern = '^\s*"(?<sshd>[^"]+)"\s+-f\s+"(?<config>[^"]+)"\s+-E\s+"(?<log>[^"]+)"\s*$'
    if ($commandLine -notmatch $pattern) {
        throw 'SSH-7311 SERVICE_NAME_COLLISION: the AegisOpenSSH command line is not the exact managed shape.'
    }
    if (-not (Test-AegisPathEqual $Matches.sshd ([string]$Manifest.sshdPath)) -or
        -not (Test-AegisPathEqual $Matches.config ([string]$Manifest.configPath)) -or
        -not (Test-AegisPathEqual $Matches.log ([string]$Manifest.logPath))) {
        throw 'SSH-7311 SERVICE_NAME_COLLISION: the AegisOpenSSH command line does not match the persisted managed paths.'
    }
}

function Test-AegisManagedFirewallRuleOwnership {
    param(
        [Parameter(Mandatory)][object]$Rule,
        [Parameter(Mandatory)][string]$RuleId,
        [Parameter(Mandatory)][string]$DisplayName,
        [Parameter(Mandatory)][string]$Group,
        [Parameter(Mandatory)][string]$Program,
        [Parameter(Mandatory)][int]$Port
    )
    try {
        if (-not ([string]$Rule.Name).Equals($RuleId, [StringComparison]::Ordinal) -or
            -not ([string]$Rule.DisplayName).Equals($DisplayName, [StringComparison]::Ordinal) -or
            -not ([string]$Rule.Group).Equals($Group, [StringComparison]::Ordinal) -or
            [string]$Rule.Enabled -ne 'True' -or
            [string]$Rule.Direction -ne 'Inbound' -or
            [string]$Rule.Action -ne 'Allow' -or
            [string]$Rule.EdgeTraversalPolicy -ne 'Block') {
            return $false
        }
        $profile = [string]$Rule.Profile
        if ($profile -match '(?i)Public|Any' -or $profile -notmatch '(?i)Private' -or $profile -notmatch '(?i)Domain') {
            return $false
        }
        $portFilters = @(Get-NetFirewallPortFilter -AssociatedNetFirewallRule $Rule -ErrorAction Stop)
        $applicationFilters = @(Get-NetFirewallApplicationFilter -AssociatedNetFirewallRule $Rule -ErrorAction Stop)
        if ($portFilters.Count -ne 1 -or $applicationFilters.Count -ne 1) { return $false }
        $portFilter = $portFilters[0]
        $applicationFilter = $applicationFilters[0]
        $actualProgram = [IO.Path]::GetFullPath([Environment]::ExpandEnvironmentVariables([string]$applicationFilter.Program))
        return [bool](
            [string]$portFilter.Protocol -in @('6', 'TCP') -and
            [string]$portFilter.LocalPort -eq [string]$Port -and
            (Test-AegisPathEqual $actualProgram $Program)
        )
    } catch {
        return $false
    }
}

function Assert-AegisManagedFirewallRuleOwnership {
    param(
        [object[]]$Rules,
        [Parameter(Mandatory)][string]$RuleId,
        [Parameter(Mandatory)][string]$DisplayName,
        [Parameter(Mandatory)][string]$Group,
        [Parameter(Mandatory)][string]$Program,
        [Parameter(Mandatory)][int]$Port
    )
    if ($Rules.Count -ne 1 -or -not (Test-AegisManagedFirewallRuleOwnership -Rule $Rules[0] -RuleId $RuleId -DisplayName $DisplayName -Group $Group -Program $Program -Port $Port)) {
        throw "SSH-7333 FIREWALL_OWNERSHIP_MISMATCH: rule ID $RuleId is absent, ambiguous, or does not match the managed Aegis filters."
    }
}

function Install-AegisManagedFirewallRule {
    param(
        [Parameter(Mandatory)][string]$RuleId,
        [Parameter(Mandatory)][string]$DisplayName,
        [Parameter(Mandatory)][string]$Group,
        [Parameter(Mandatory)][string]$Program,
        [Parameter(Mandatory)][int]$Port
    )
    $existingRules = @(Get-NetFirewallRule -Name $RuleId -ErrorAction SilentlyContinue)
    if ($existingRules.Count -gt 0) {
        Assert-AegisManagedFirewallRuleOwnership -Rules $existingRules -RuleId $RuleId -DisplayName $DisplayName -Group $Group -Program $Program -Port $Port
        $existingRules | Remove-NetFirewallRule
    }
    New-NetFirewallRule `
        -Name $RuleId `
        -DisplayName $DisplayName `
        -Group $Group `
        -Direction Inbound `
        -Action Allow `
        -Enabled True `
        -Profile Private,Domain `
        -Protocol TCP `
        -LocalPort $Port `
        -Program $Program `
        -EdgeTraversalPolicy Block | Out-Null
}

function Remove-AegisManagedFirewallRule {
    param(
        [Parameter(Mandatory)][string]$RuleId,
        [Parameter(Mandatory)][string]$DisplayName,
        [Parameter(Mandatory)][string]$Group,
        [Parameter(Mandatory)][string]$Program,
        [Parameter(Mandatory)][int]$Port
    )
    $existingRules = @(Get-NetFirewallRule -Name $RuleId -ErrorAction SilentlyContinue)
    if ($existingRules.Count -eq 0) { return }
    Assert-AegisManagedFirewallRuleOwnership -Rules $existingRules -RuleId $RuleId -DisplayName $DisplayName -Group $Group -Program $Program -Port $Port
    $existingRules | Remove-NetFirewallRule
}

function Assert-AegisManagedCaller {
    param([Parameter(Mandatory)][object]$Manifest)
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    $isAdministrator = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    $currentSid = if ($identity.User) { $identity.User.Value } else { '' }
    if (-not $isAdministrator -and -not $currentSid.Equals([string]$Manifest.authorizedSid, [StringComparison]::OrdinalIgnoreCase)) {
        throw "SSH-7327 ENROLLMENT_CALLER_MISMATCH: SID $currentSid cannot manage keys for SID $($Manifest.authorizedSid)."
    }
}
