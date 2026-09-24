using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace WhisperOffline;

/// Dekodiert Audio- und Videodateien über Windows Media Foundation zu
/// 16-kHz-Mono-Floats für die geladene Engine.
///
/// whisper-cli bringt nur Decoder für WAV, MP3, FLAC und Ogg Vorbis mit und
/// kennt Parakeet-Modelle nicht. Media Foundation deckt dagegen ab, was
/// Windows selbst abspielen kann: MP4/M4A/AAC, WMA, MP3, WAV, ab Windows 10
/// auch FLAC, dazu Videos mit Tonspur.
public static class AudioFileDecoder
{
    private const int TargetRate = 16000;

    /// null, wenn Media Foundation das Format nicht kennt (z. B. Ogg) oder
    /// fehlt (Windows-N-Editionen ohne Media Feature Pack).
    public static float[]? TryDecode(string path)
    {
        try
        {
            using var reader = new MediaFoundationReader(path);
            ISampleProvider sp = reader.ToSampleProvider();
            if (sp.WaveFormat.Channels > 1) sp = new Downmix(sp);
            if (sp.WaveFormat.SampleRate != TargetRate) sp = new WdlResamplingSampleProvider(sp, TargetRate);

            var output = new List<float>(TargetRate * 60);
            var buf = new float[TargetRate];
            int n;
            while ((n = sp.Read(buf, 0, buf.Length)) > 0)
                output.AddRange(new ArraySegment<float>(buf, 0, n));
            return output.ToArray();
        }
        catch
        {
            return null;
        }
    }

    /// Mittelt beliebig viele Kanäle zu einem. NAudios StereoToMono kann nur zwei.
    private sealed class Downmix : ISampleProvider
    {
        private readonly ISampleProvider source;
        private readonly int channels;
        private float[] buffer = Array.Empty<float>();

        public Downmix(ISampleProvider source)
        {
            this.source = source;
            channels = source.WaveFormat.Channels;
            WaveFormat = WaveFormat.CreateIeeeFloatWaveFormat(source.WaveFormat.SampleRate, 1);
        }

        public WaveFormat WaveFormat { get; }

        public int Read(float[] dest, int offset, int count)
        {
            int needed = count * channels;
            if (buffer.Length < needed) buffer = new float[needed];
            int frames = source.Read(buffer, 0, needed) / channels;
            for (int f = 0; f < frames; f++)
            {
                float acc = 0;
                for (int c = 0; c < channels; c++) acc += buffer[f * channels + c];
                dest[offset + f] = acc / channels;
            }
            return frames;
        }
    }
}
