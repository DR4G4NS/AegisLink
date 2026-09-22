[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Path,
    [Parameter(Mandatory)][string]$PfxPath,
    [string]$TimestampUrl = 'http://timestamp.digicert.com'
)

$ErrorActionPreference = 'Stop'
$artifact = (Resolve-Path -LiteralPath $Path).Path
$certificate = (Resolve-Path -LiteralPath $PfxPath).Path
$password = $env:AEGIS_WINDOWS_PFX_PASSWORD
if ([string]::IsNullOrWhiteSpace($password)) { throw 'AEGIS_WINDOWS_PFX_PASSWORD is required' }

$signtool = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\signtool.exe" |
    Sort-Object FullName -Descending |
    Select-Object -First 1
if (-not $signtool) { throw 'Windows SDK signtool.exe was not found' }

& $signtool.FullName sign /fd SHA256 /td SHA256 /tr $TimestampUrl /f $certificate /p $password $artifact
if ($LASTEXITCODE -ne 0) { throw "signtool failed with exit code $LASTEXITCODE" }

& $signtool.FullName verify /pa /all /v $artifact
if ($LASTEXITCODE -ne 0) { throw "Authenticode verification failed with exit code $LASTEXITCODE" }

$signature = Get-AuthenticodeSignature -LiteralPath $artifact
if ($signature.Status -ne 'Valid') { throw "Authenticode status is $($signature.Status)" }
$expectedSubject = $env:AEGIS_WINDOWS_CERT_SUBJECT
if (-not [string]::IsNullOrWhiteSpace($expectedSubject) -and $signature.SignerCertificate.Subject -ne $expectedSubject) {
    throw "Unexpected signer subject: $($signature.SignerCertificate.Subject)"
}
Write-Host "Verified Authenticode signer: $($signature.SignerCertificate.Subject)"
