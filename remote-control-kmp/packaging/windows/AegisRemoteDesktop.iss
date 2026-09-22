#define AppName "Aegis Remote Desktop"
#ifndef AppVersion
  #define AppVersion "0.2.0"
#endif
#define AppPublisher "Aegis Remote Control"
#define AppExeName "Aegis Remote Desktop.exe"
#define AppSourceDir "..\..\app-desktop\desktop-main\build\compose\binaries\main\app\Aegis Remote Desktop"
#ifndef OpenSshSourceDir
  #define OpenSshSourceDir "openssh"
#endif
#ifndef BundledOpenSshDir
  #define BundledOpenSshDir "openssh\bin\OpenSSH-Win64"
#endif
#define AegisSshPort "48222"

[Setup]
AppId={{8D87FA7D-7C2D-4C23-9F29-4F3CB67FD6D1}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
VersionInfoVersion={#AppVersion}
VersionInfoProductVersion={#AppVersion}
VersionInfoCompany={#AppPublisher}
VersionInfoDescription={#AppName} verified Windows installer
VersionInfoProductName={#AppName}
DefaultDirName={autopf}\Aegis Remote Desktop
DefaultGroupName=Aegis Remote Control
DisableProgramGroupPage=yes
OutputDir=..\..\dist\Aegis-Remote-0.2.0
OutputBaseFilename=Aegis-Remote-Desktop-Setup-{#AppVersion}
SetupIconFile=..\..\app-desktop\desktop-main\src\main\package\windows\aegis.ico
LicenseFile=..\..\app-desktop\desktop-main\src\main\package\LICENSE.txt
UninstallDisplayName={#AppName}
UninstallDisplayIcon={app}\{#AppExeName}
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
SetupMutex=AegisRemoteDesktopSetup
CloseApplications=force
RestartApplications=no
SetupLogging=yes
MinVersion=10.0.17763
#ifdef SignedBuild
SignTool=aegissign
SignedUninstaller=yes
#endif

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "spanish"; MessagesFile: "compiler:Languages\Spanish.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#AppSourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#OpenSshSourceDir}\*.ps1"; DestDir: "{app}\provisioning\openssh"; Flags: ignoreversion
Source: "{#BundledOpenSshDir}\*"; DestDir: "{app}\provisioning\openssh\bin\OpenSSH-Win64"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\..\tools\release\verify-windows-update.ps1"; DestDir: "{app}\tools"; Flags: ignoreversion

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExeName}"
Name: "{group}\{cm:UninstallAegis}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(AppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File ""{app}\provisioning\openssh\manage-aegis-pairing-firewall.ps1"" -Mode Uninstall"; Flags: runhidden waituntilterminated; RunOnceId: "RemoveAegisPairingFirewall"
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File ""{app}\provisioning\openssh\uninstall-aegis-openssh.ps1"" -RemoveCapabilityInstalledByAegis"; Flags: runhidden waituntilterminated; RunOnceId: "RemoveAegisOpenSsh"

[CustomMessages]
english.ConfigureFirewall=Configuring secure local pairing on private networks...
spanish.ConfigureFirewall=Configurando la vinculación local segura en redes privadas...
english.ProvisionOpenSsh=Provisioning the isolated Aegis OpenSSH service on private port {#AegisSshPort}...
spanish.ProvisionOpenSsh=Configurando el servicio OpenSSH aislado de Aegis en el puerto privado {#AegisSshPort}...
english.UninstallAegis=Uninstall Aegis Remote Desktop
spanish.UninstallAegis=Desinstalar Aegis Remote Desktop

[Code]
function NextVersionPart(var Version: String): Integer;
var
  Separator: Integer;
  Part: String;
begin
  Separator := Pos('.', Version);
  if Separator = 0 then
  begin
    Part := Version;
    Version := '';
  end
  else
  begin
    Part := Copy(Version, 1, Separator - 1);
    Delete(Version, 1, Separator);
  end;
  Result := StrToIntDef(Part, 0);
end;

function CompareVersions(LeftVersion, RightVersion: String): Integer;
var
  LeftPart: Integer;
  RightPart: Integer;
begin
  Result := 0;
  while (LeftVersion <> '') or (RightVersion <> '') do
  begin
    LeftPart := NextVersionPart(LeftVersion);
    RightPart := NextVersionPart(RightVersion);
    if LeftPart < RightPart then
    begin
      Result := -1;
      Exit;
    end;
    if LeftPart > RightPart then
    begin
      Result := 1;
      Exit;
    end;
  end;
end;

function InitializeSetup(): Boolean;
var
  InstalledVersion: String;
  UninstallKey: String;
begin
  Result := True;
  UninstallKey := 'SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\{8D87FA7D-7C2D-4C23-9F29-4F3CB67FD6D1}_is1';
  if RegQueryStringValue(HKLM64, UninstallKey, 'DisplayVersion', InstalledVersion) and
     (CompareVersions(InstalledVersion, '{#AppVersion}') > 0) then
  begin
    MsgBox(
      Format('PKG-9007: Aegis Remote Desktop %s is already installed. This verified Setup is older (%s), so the downgrade was blocked.', [InstalledVersion, '{#AppVersion}']),
      mbError,
      MB_OK);
    Result := False;
  end;
end;

procedure StopLeftoverHostProcesses;
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{sys}\sc.exe'), 'stop AegisOpenSSH', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM "Aegis Remote Desktop.exe" /T', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  NeedsRestart := False;
  StopLeftoverHostProcesses;
  Result := '';
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  PowerShellPath: String;
  ProvisioningScript: String;
  ProvisioningParameters: String;
  PairingFirewallScript: String;
  PairingFirewallParameters: String;
  ResultCode: Integer;
begin
  if CurStep = ssPostInstall then
  begin
    PowerShellPath := ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe');
    ProvisioningScript := ExpandConstant('{app}\provisioning\openssh\invoke-aegis-openssh-provisioning.ps1');
    ProvisioningParameters := '-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' +
      ProvisioningScript + '" -Port {#AegisSshPort}';
    WizardForm.StatusLabel.Caption := ExpandConstant('{cm:ProvisionOpenSsh}');
    if not Exec(PowerShellPath, ProvisioningParameters, '', SW_HIDE, ewWaitUntilTerminated, ResultCode) then
      RaiseException('SSH-7301: Windows could not start the Aegis OpenSSH provisioner.');
    if ResultCode <> 0 then
      RaiseException(Format('SSH-7301: Aegis OpenSSH provisioning failed with exit code %d. Review %%ProgramData%%\Aegis\installer\openssh-install.log.', [ResultCode]));
    PairingFirewallScript := ExpandConstant('{app}\provisioning\openssh\manage-aegis-pairing-firewall.ps1');
    PairingFirewallParameters := '-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' +
      PairingFirewallScript + '" -Mode Install';
    WizardForm.StatusLabel.Caption := ExpandConstant('{cm:ConfigureFirewall}');
    if not Exec(PowerShellPath, PairingFirewallParameters, '', SW_HIDE, ewWaitUntilTerminated, ResultCode) then
      RaiseException('PKG-9027: Windows could not start the managed pairing firewall provisioner.');
    if ResultCode <> 0 then
      RaiseException(Format('PKG-9027: Aegis pairing firewall provisioning failed with exit code %d. Review the Windows installer log.', [ResultCode]));
  end;
end;
