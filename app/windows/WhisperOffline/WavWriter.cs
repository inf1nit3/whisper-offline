using System.IO;

namespace WhisperOffline;

/// Schreibt 16-kHz-Mono-Floats als 16-Bit-PCM-WAV.
public static class WavWriter
{
    public static void Write16kMono(string path, float[] samples)
    {
        using var fs = new FileStream(path, FileMode.Create, FileAccess.Write);
        using var w = new BinaryWriter(fs);

        int dataLen = samples.Length * 2;
        w.Write("RIFF"u8);
        w.Write(36 + dataLen);
        w.Write("WAVE"u8);
        w.Write("fmt "u8);
        w.Write(16);          // fmt-Chunk-Größe
        w.Write((short)1);    // PCM
        w.Write((short)1);    // Mono
        w.Write(16000);       // Sample-Rate
        w.Write(32000);       // Byte-Rate
        w.Write((short)2);    // Block-Align
        w.Write((short)16);   // Bits pro Sample
        w.Write("data"u8);
        w.Write(dataLen);
        foreach (var s in samples)
        {
            var v = (int)(s * 32767f);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            w.Write((short)v);
        }
    }
}
