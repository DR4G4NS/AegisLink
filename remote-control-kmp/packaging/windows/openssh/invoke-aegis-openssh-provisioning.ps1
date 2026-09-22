[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 48222
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$logDirectory = Join-Path $env:ProgramData 'Aegis\installer'
$logPath = Join-Path $logDirectory 'openssh-install.log'
$provisioner = Join-Path $PSScriptRoot 'install-aegis-openssh.ps1'

New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
try {
    "[$([DateTimeOffset]::Now.ToString('O'))] Starting Aegis OpenSSH provisioning on port $Port." |
        Out-File -LiteralPath $logPath -Encoding utf8
    & $provisioner -Port $Port *>> $logPath
    if ($LASTEXITCODE -ne 0) {
        throw "The OpenSSH provisioner returned exit code $LASTEXITCODE."
    }
    "[$([DateTimeOffset]::Now.ToString('O'))] Provisioning completed successfully." |
        Out-File -LiteralPath $logPath -Encoding utf8 -Append
    exit 0
} catch {
    $failure = "[$([DateTimeOffset]::Now.ToString('O'))] FAILED: $($_.Exception.Message)`r`n$($_.ScriptStackTrace)"
    $failure | Out-File -LiteralPath $logPath -Encoding utf8 -Append
    Write-Error $failure
    exit 1
}
