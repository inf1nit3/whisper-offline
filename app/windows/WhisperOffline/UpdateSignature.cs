using System.IO;
using System.Security.Cryptography;

namespace WhisperOffline;

/// Prüft die Signatur eines Update-Zips, bevor es installiert wird.
///
/// Der Updater ersetzt die komplette Installation und startet sie neu — ohne
/// Prüfung würde jedes Zip ausgeführt, das im GitHub-Release liegt. Signiert
/// wird beim Release mit dem privaten Schlüssel aus keystore/ (nur auf dem
/// Build-Rechner, nie im Repo):
///   openssl dgst -sha256 -sign keystore/windows-update-key.pem -out x.zip.sig x.zip
public static class UpdateSignature
{
    /// Öffentlicher Schlüssel (ECDSA P-256, SubjectPublicKeyInfo, Base64).
    private const string PublicKey =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEoQe7f6TJ2wUSFCLEsSOWgrIAdXYYKZ3kGZQabOXAn1weJsFrS7D9q/yBoGo25tksXuYh3/S64P0UxcZpdMAbKQ==";

    /// true nur bei gültiger Signatur (DER, wie openssl sie erzeugt).
    public static bool Verify(string filePath, byte[] signature)
    {
        try
        {
            using var ecdsa = ECDsa.Create();
            ecdsa.ImportSubjectPublicKeyInfo(Convert.FromBase64String(PublicKey), out _);
            using var data = File.OpenRead(filePath);
            return ecdsa.VerifyData(data, signature, HashAlgorithmName.SHA256,
                                    DSASignatureFormat.Rfc3279DerSequence);
        }
        catch (CryptographicException)
        {
            return false;
        }
    }
}
