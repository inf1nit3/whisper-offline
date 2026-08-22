using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace WhisperOffline;

/// Mikrofonaufnahme über WASAPI (Shared Mode). Liefert 16-kHz-Mono-Floats
/// durch Downmix und lineares Resampling der Eingangsrate.
public sealed class AudioRecorder
{
    private WasapiCapture? capture;
    private List<float> mono = new(16000 * 30);
    private int sourceRate = 48000;

    public bool IsRecording => capture != null;

    public bool Start()
    {
        if (IsRecording) return true;
        try
        {
            var cap = new WasapiCapture();
            sourceRate = cap.WaveFormat.SampleRate;
            int channels = cap.WaveFormat.Channels;
            mono = new List<float>(16000 * 30);

            cap.DataAvailable += (_, e) =>
            {
                // IEEE-Float-Samples (WASAPI Shared liefert 32-Bit-Float)
                int floatCount = e.BytesRecorded / 4;
                var buffer = new float[floatCount];
                System.Buffer.BlockCopy(e.Buffer, 0, buffer, 0, e.BytesRecorded);
                for (int i = 0; i < floatCount; i += channels)
                {
                    float acc = 0;
                    int used = 0;
                    for (int c = 0; c < channels && i + c < floatCount; c++) { acc += buffer[i + c]; used++; }
                    if (used > 0) mono.Add(acc / used);
                }
            };

            cap.StartRecording();
            capture = cap;
            return true;
        }
        catch
        {
            capture?.Dispose();
            capture = null;
            return false;
        }
    }

    public float[] Stop()
    {
        var cap = capture;
        capture = null;
        if (cap != null)
        {
            try { cap.StopRecording(); } catch { }
            Thread.Sleep(150); // letzte Puffer abholen
            cap.Dispose();
        }
        return Resample(mono.ToArray(), sourceRate, 16000);
    }

    private static float[] Resample(float[] input, int from, int to)
    {
        if (from == to || input.Length == 0) return input;
        double ratio = (double)from / to;
        int outLen = (int)(input.Length / ratio);
        var output = new float[outLen];
        for (int i = 0; i < outLen; i++)
        {
            double src = i * ratio;
            int i0 = (int)src;
            int i1 = Math.Min(i0 + 1, input.Length - 1);
            double frac = src - i0;
            output[i] = (float)(input[i0] * (1 - frac) + input[i1] * frac);
        }
        return output;
    }
}
