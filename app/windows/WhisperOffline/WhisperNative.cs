using System.Runtime.InteropServices;
using System.IO;

namespace WhisperOffline;

/// Engine im eigenen Prozess über whisper_shim.dll.
///
/// Der Unterschied zu <see cref="WhisperCli"/>: das Modell bleibt zwischen zwei
/// Diktaten geladen. Der Unterprozess-Weg las bei jedem Tastendruck die
/// komplette Modelldatei neu von der Platte — für ein Werkzeug, das im
/// Hintergrund auf einen Hotkey wartet, ist das der größte Einzelposten.
///
/// Schlägt das Laden der DLL fehl (fehlende Datei, falsche Architektur), meldet
/// <see cref="Available"/> false und der Aufrufer fällt auf whisper-cli zurück.
public static class WhisperNative
{
    private const string Dll = "whisper_shim";

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool SetDllDirectoryW(string? lpPathName);

    /// whisper_shim.dll liegt bei den übrigen Engine-DLLs in engine/, nicht neben
    /// der EXE. Beides muss aufgelöst werden: die DLL selbst über einen Resolver,
    /// ihre Abhängigkeiten (libwhisper.dll, ggml*.dll) und die zur Laufzeit
    /// nachgeladenen CPU-Varianten über das Suchverzeichnis.
    static WhisperNative()
    {
        try
        {
            if (OperatingSystem.IsWindows()) SetDllDirectoryW(WhisperCli.EngineDir);
            NativeLibrary.SetDllImportResolver(typeof(WhisperNative).Assembly, (name, _, _) =>
            {
                if (name != Dll) return IntPtr.Zero;
                var path = Path.Combine(WhisperCli.EngineDir, "whisper_shim.dll");
                return NativeLibrary.TryLoad(path, out var handle) ? handle : IntPtr.Zero;
            });
        }
        catch { }
    }

    [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
    private static extern int ws_load([MarshalAs(UnmanagedType.LPUTF8Str)] string modelPath, int useGpu);

    [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
    private static extern int ws_is_loaded();

    [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
    private static extern void ws_free();

    [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
    private static extern IntPtr ws_transcribe(float[] samples, int nSamples,
        [MarshalAs(UnmanagedType.LPUTF8Str)] string lang, int shortCtx);

    [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
    private static extern void ws_string_free(IntPtr s);

    [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
    private static extern IntPtr ws_backend_info();

    [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
    private static extern IntPtr ws_last_timings();

    [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
    private static extern int ws_last_audio_ctx();

    [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
    private static extern int ws_threads();

    [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
    private static extern int ws_engine_kind();

    /// Parakeet ist mehrsprachig und hat kein festes Encoder-Fenster — dort sind
    /// Sprachauswahl und Kurzaudio-Schalter wirkungslos.
    public static bool IsParakeet => Available && ws_engine_kind() == 2;

    private static bool? available;
    private static string loadedModel = "";
    private static readonly object gate = new();

    /// Lässt sich die DLL überhaupt laden? Wird einmalig geprüft.
    public static bool Available
    {
        get
        {
            if (available.HasValue) return available.Value;
            try
            {
                _ = ws_threads();
                available = true;
            }
            catch (DllNotFoundException) { available = false; }
            catch (EntryPointNotFoundException) { available = false; }
            catch (BadImageFormatException) { available = false; }
            return available.Value;
        }
    }

    public static string BackendInfo =>
        Available ? Marshal.PtrToStringUTF8(ws_backend_info()) ?? "" : "";

    public static string LastTimings =>
        Available ? Marshal.PtrToStringUTF8(ws_last_timings()) ?? "" : "";

    public static int LastAudioCtx => Available ? ws_last_audio_ctx() : 0;

    /// Lädt das Modell, falls noch nicht geschehen. Mehrfachaufrufe mit
    /// demselben Pfad sind billig — die Engine erkennt das selbst.
    public static bool EnsureLoaded(string modelPath)
    {
        if (!Available || !File.Exists(modelPath)) return false;
        lock (gate)
        {
            if (loadedModel == modelPath && ws_is_loaded() != 0) return true;
            if (ws_load(modelPath, 0) == 0) return false;
            loadedModel = modelPath;
            return true;
        }
    }

    public static void Unload()
    {
        if (!Available) return;
        lock (gate)
        {
            ws_free();
            loadedModel = "";
        }
    }

    /// 16-kHz-Mono-Samples in [-1, 1]. Gibt null zurück, wenn nichts erkannt wurde.
    public static string? Transcribe(float[] samples, string language, bool shortCtx)
    {
        if (!Available) return null;
        lock (gate)
        {
            var ptr = ws_transcribe(samples, samples.Length, language, shortCtx ? 1 : 0);
            if (ptr == IntPtr.Zero) return null;
            try { return Marshal.PtrToStringUTF8(ptr); }
            finally { ws_string_free(ptr); }
        }
    }
}
