using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace AegisRemoteUninstaller;

internal static class Program
{
    private const string ProductName = "Aegis Remote Desktop";
    private const string ProductPublisher = "Aegis Remote Control";
    private const uint MbOk = 0x00000000;
    private const uint MbYesNo = 0x00000004;
    private const uint MbIconInformation = 0x00000040;
    private const uint MbIconWarning = 0x00000030;
    private const int IdYes = 6;

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int MessageBoxW(nint hWnd, string text, string caption, uint type);

    [DllImport("kernel32.dll")]
    private static extern ushort GetUserDefaultUILanguage();

    [STAThread]
    private static int Main()
    {
        var copy = Copy.ForCurrentWindowsUser();
        var uninstallCommand = FindUninstallCommand();
        if (uninstallCommand is null)
        {
            MessageBoxW(
                0,
                copy.NotInstalled,
                "Aegis Remote Desktop",
                MbOk | MbIconInformation
            );
            return 0;
        }

        var choice = MessageBoxW(
            0,
            copy.ConfirmRemoval,
            copy.UninstallTitle,
            MbYesNo | MbIconWarning
        );
        if (choice != IdYes)
        {
            return 0;
        }

        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = uninstallCommand.FileName,
                Arguments = uninstallCommand.Arguments,
                UseShellExecute = false,
            });
            return 0;
        }
        catch (Exception error)
        {
            MessageBoxW(
                0,
                $"{copy.StartFailed}\n\n{error.Message}",
                "Aegis Remote Desktop",
                MbOk | MbIconWarning
            );
            return 1;
        }
    }

    private static UninstallCommand? FindUninstallCommand()
    {
        // This is a per-user package. Never elevate or execute an arbitrary
        // UninstallString obtained from the user-writable HKCU registry.
        foreach (var hive in new[] { RegistryHive.CurrentUser })
        {
            foreach (var view in new[] { RegistryView.Registry64, RegistryView.Registry32 })
            {
                using var baseKey = RegistryKey.OpenBaseKey(hive, view);
                using var uninstall = baseKey.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall");
                if (uninstall is null)
                {
                    continue;
                }

                foreach (var subKeyName in uninstall.GetSubKeyNames())
                {
                    using var product = uninstall.OpenSubKey(subKeyName);
                    var displayName = product?.GetValue("DisplayName") as string;
                    if (!string.Equals(displayName, ProductName, StringComparison.OrdinalIgnoreCase))
                    {
                        continue;
                    }

                    var publisher = product?.GetValue("Publisher") as string;
                    if (!string.Equals(publisher, ProductPublisher, StringComparison.Ordinal))
                    {
                        continue;
                    }

                    var windowsInstaller = Convert.ToInt32(product?.GetValue("WindowsInstaller") ?? 0) == 1;
                    if (windowsInstaller && Guid.TryParse(subKeyName, out _))
                    {
                        return new UninstallCommand(
                            Path.Combine(Environment.SystemDirectory, "msiexec.exe"),
                            $"/x \"{subKeyName}\""
                        );
                    }
                }
            }
        }

        return null;
    }

    private sealed record UninstallCommand(string FileName, string Arguments);

    private sealed record Copy(
        string NotInstalled,
        string ConfirmRemoval,
        string UninstallTitle,
        string StartFailed
    )
    {
        private const ushort PrimaryLanguageMask = 0x03ff;
        private const ushort SpanishPrimaryLanguage = 0x000a;

        public static Copy ForCurrentWindowsUser()
        {
            var primaryLanguage = (ushort)(GetUserDefaultUILanguage() & PrimaryLanguageMask);
            return primaryLanguage == SpanishPrimaryLanguage
                ? new Copy(
                    "Aegis Remote Desktop no está instalado para este usuario de Windows.",
                    "¿Quieres quitar Aegis Remote Desktop, sus accesos directos y la regla de firewall para vinculación en redes privadas?",
                    "Desinstalar Aegis Remote Desktop",
                    "Windows no pudo iniciar el desinstalador."
                )
                : new Copy(
                    "Aegis Remote Desktop is not installed for this Windows user.",
                    "Remove Aegis Remote Desktop, its shortcuts, and its private-network pairing firewall rule?",
                    "Uninstall Aegis Remote Desktop",
                    "Windows could not start the uninstaller."
                );
        }
    }
}
