using System.IO;
using Avalonia.Threading;

namespace WhisperOffline;

// Diktat per Hotkey: Aufnehmen, Erkennen, Nachbearbeiten, Einfügen —
// und „Auswahl bearbeiten“. Teil von MainWindow.
public partial class MainWindow
{
    private FormatMode dictationMode = FormatMode.Standard;
    private bool dictationExcluded;
    /// Enhance für dieses Diktat: true = zweiter Hotkey, null = Einstellung.
    private bool? dictationEnhance;
    private LiveTranscriber? dictationLive;
    private CancellationTokenSource? dictationCts;
    private DictationOverlay? overlay;

    private DictationOverlay Overlay => overlay ??= new DictationOverlay();

    /// Alle Belegungen aus den Einstellungen an den Hotkey-Thread geben.
    private void ApplyHotkeys()
    {
        HotkeyHook.SetBinding(HotkeyAction.Dictate, settings.HotkeyModifiers, settings.HotkeyVk);
        HotkeyHook.SetBinding(HotkeyAction.DictateEnhance, settings.EnhanceHotkeyModifiers, settings.EnhanceHotkeyVk);
        HotkeyHook.SetBinding(HotkeyAction.Transform, settings.TransformHotkeyModifiers, settings.TransformHotkeyVk);
        HotkeyHook.SetSingleKey(settings.SingleKeyVk);
    }

    private void SetHotkey(HotkeyAction target, uint mods, uint vk)
    {
        switch (target)
        {
            case HotkeyAction.Dictate: settings.HotkeyModifiers = mods; settings.HotkeyVk = vk; break;
            case HotkeyAction.DictateEnhance: settings.EnhanceHotkeyModifiers = mods; settings.EnhanceHotkeyVk = vk; break;
            case HotkeyAction.Transform: settings.TransformHotkeyModifiers = mods; settings.TransformHotkeyVk = vk; break;
        }
        settings.Save();
        HotkeyHook.SetBinding(target, mods, vk);
        // RegisterHotKey läuft asynchron auf dem Hotkey-Thread; kurz warten,
        // damit BindOk den neuen Stand zeigt.
        Dispatcher.UIThread.Post(async () => { await Task.Delay(150); UpdateHotkeyLabel(); });
        UpdateHotkeyLabel();
    }

    private void OnHotkey(HotkeyAction action, HotkeyEdge edge)
    {
        switch (action)
        {
            case HotkeyAction.Dictate:
            case HotkeyAction.DictateEnhance:
            {
                bool? enhance = action == HotkeyAction.DictateEnhance ? true : null;
                if (settings.PushToTalk)
                {
                    if (edge == HotkeyEdge.Pressed && !dictating) StartDictation(enhance);
                    else if (edge == HotkeyEdge.Released && dictating) _ = StopDictationAsync();
                }
                else if (edge == HotkeyEdge.Pressed)
                {
                    if (dictating) _ = StopDictationAsync();
                    else StartDictation(enhance);
                }
                break;
            }
            case HotkeyAction.SingleKey:
                if (settings.PushToTalk)
                {
                    if (edge == HotkeyEdge.Pressed && !dictating) StartDictation(null);
                    else if (edge == HotkeyEdge.Released && dictating) _ = StopDictationAsync();
                    else if (edge == HotkeyEdge.Cancelled && dictating) CancelDictation();
                }
                else if (edge == HotkeyEdge.Released)
                {
                    // Antippen: an/aus. Gedrückt halten mit anderer Taste = normaler Kurzbefehl
                    if (dictating) _ = StopDictationAsync();
                    else StartDictation(null);
                }
                break;
            case HotkeyAction.Transform:
                if (edge == HotkeyEdge.Pressed) _ = TransformSelectionAsync();
                break;
        }
    }

    /// Modus und Ausschluss für das Zielfenster bestimmen.
    private (FormatMode mode, bool excluded) TargetContext(IntPtr hwnd)
    {
        var proc = ForegroundInfo.Process(hwnd);
        settings.AppProfiles.TryGetValue(proc, out var profile);
        if (profile?.Excluded == true) return (FormatMode.Raw, true);
        if (settings.AppContext) return (FormatModes.Detect(proc, ForegroundInfo.Title(hwnd), profile?.Mode), false);
        return (profile?.Mode is { } m && m != FormatMode.Auto ? m : FormatMode.Standard, false);
    }

    private void StartDictation(bool? enhance)
    {
        if (busy || pickerOpen || downloading != null || dictating) return;
        // Läuft gerade eine Aufnahme im Hauptfenster oder ein Meeting, gehört das Mikrofon dort hin.
        if (recorder.IsRecording || MeetingActive) return;
        if (!File.Exists(WhisperCli.SelectedModel)) return;

        dictationTarget = PasteHelper.ForegroundWindow();
        if (dictationTarget == IntPtr.Zero) return;
        // Ein laufender Stapel gibt die Engine frei; die Datei kommt danach erneut dran
        if (batchRunning) WhisperNative.Cancel();
        (dictationMode, dictationExcluded) = TargetContext(dictationTarget);
        dictationEnhance = enhance;
        if (!recorder.Start())
        {
            if (settings.AudioFeedback) Cues.Error();
            StatusLabel.Text = "Mikrofon konnte nicht geöffnet werden.";
            return;
        }
        dictating = true;
        bool willEnhance = WillEnhance();
        StatusLabel.Text = "🎙 Diktat läuft — " + HotkeySpec.Describe(settings.HotkeyModifiers, settings.HotkeyVk) +
                           (settings.PushToTalk ? " loslassen" : " beendet & fügt ein");
        if (settings.ShowOverlay)
            Overlay.ShowRecording(() => recorder.Level, recorder.ConsumePeak,
                willEnhance ? "✨ " + dictationMode.Label() : "");
        if (settings.LiveTranscription && WhisperNative.Available)
        {
            dictationLive = new LiveTranscriber(recorder, settings, Lang, settings.Translate,
                t => Dispatcher.UIThread.Post(() => { if (dictating && settings.ShowOverlay) Overlay.SetLive(t); }));
            dictationLive.Start();
        }
    }

    private bool WillEnhance() =>
        !dictationExcluded && dictationMode != FormatMode.Raw &&
        (dictationEnhance ?? settings.EnhanceAuto) && Enhancer.IsConfigured(settings);

    /// Einzeltaste mit anderer Taste kombiniert: Aufnahme verwerfen.
    private void CancelDictation()
    {
        if (!dictating) return;
        dictationLive?.Stop();
        dictationLive = null;
        recorder.Stop(cue: false);
        dictating = false;
        overlay?.HideOverlay();
        StatusLabel.Text = "Diktat verworfen";
    }

    private async Task StopDictationAsync()
    {
        if (!dictating || !recorder.IsRecording) return;
        var samples = recorder.Stop();
        dictating = false;
        var live = dictationLive;
        dictationLive = null;
        if (samples.Length < 1600) // < 0,1 s
        {
            live?.Stop();
            overlay?.HideOverlay();
            StatusLabel.Text = "Diktat zu kurz";
            return;
        }

        busy = true;
        try
        {
            StatusLabel.Text = "Transkribiere Diktat…";
            if (settings.ShowOverlay) Overlay.ShowWorking("Transkribiere …");
            var text = (await TranscribeWithProgress(samples, live)).Trim();
            if (text.Length == 0)
            {
                overlay?.HideOverlay();
                StatusLabel.Text = WhisperNative.WasCancelled ? "Diktat abgebrochen" : "Kein Text erkannt";
                return;
            }

            bool enhance = WillEnhance();
            if (enhance && settings.ShowOverlay) Overlay.ShowWorking("✨ Verbessere …");
            dictationCts = new CancellationTokenSource();
            var r = await TextPipeline.FinishDictationAsync(settings, text, Transcriber.DictationLanguage(settings),
                dictationMode, enhance, dictationCts.Token);
            overlay?.HideOverlay();

            TranscriptBox.Text = TranscriptBox.Text?.Length > 0 ? TranscriptBox.Text + "\n\n" + r.Text : r.Text;
            AddHistory(r.Text, samples.Length / 16000f, "dictation");
            await Paster.PasteAsync(dictationTarget, r.Text, settings);
            StatusLabel.Text = "Diktat eingefügt ✓" + (r.Note != null ? " — " + r.Note : "");
        }
        finally
        {
            overlay?.HideOverlay();
            busy = false;
        }
    }

    // ---------- Auswahl bearbeiten (Hotkey) ----------

    private AudioRecorder? instructionRecorder;

    private async Task TransformSelectionAsync()
    {
        if (busy || dictating) return;
        if (!Enhancer.IsConfigured(settings))
        {
            StatusLabel.Text = "„Auswahl bearbeiten“ braucht ein Sprachmodell — Einstellungen → Enhance.";
            Show(); Activate();
            return;
        }
        var target = PasteHelper.ForegroundWindow();
        if (TargetContext(target).excluded)
        {
            StatusLabel.Text = "Diese App ist von Enhance ausgeschlossen (App-Profile).";
            return;
        }
        var (text, saved) = await Paster.CopySelectionAsync();
        if (string.IsNullOrWhiteSpace(text))
        {
            if (saved != null) ClipboardSnapshot.Restore(saved);
            if (settings.AudioFeedback) Cues.Error();
            StatusLabel.Text = "Keine Auswahl gefunden — erst Text markieren.";
            return;
        }
        if (text.Length > 6000)
        {
            if (saved != null) ClipboardSnapshot.Restore(saved);
            StatusLabel.Text = "Höchstens 6000 Zeichen markieren.";
            return;
        }

        var win = new TransformWindow(text, StartInstruction, StopInstructionAsync);
        win.Show();
        var choice = await win.Result;
        if (choice == null)
        {
            if (saved != null) ClipboardSnapshot.Restore(saved);
            return;
        }

        busy = true;
        try
        {
            if (settings.ShowOverlay) Overlay.ShowWorking("✨ " + choice.Value.Label + " …");
            var lang = TextRules.GuessLanguage(text);
            if (lang.Length == 0) lang = Transcriber.DictationLanguage(settings);
            dictationCts = new CancellationTokenSource();
            var o = await Enhancer.TransformAsync(settings, text, choice.Value.Instruction, lang, dictationCts.Token);
            overlay?.HideOverlay();
            if (o.Text == null)
            {
                if (saved != null) ClipboardSnapshot.Restore(saved);
                if (settings.AudioFeedback && !o.Cancelled) Cues.Error();
                StatusLabel.Text = o.Cancelled ? "Abgebrochen — Auswahl unverändert" : "Auswahl unverändert: " + o.Error;
                return;
            }
            ClipboardSnapshot.SetText(o.Text);
            uint ours = ClipboardSnapshot.Sequence;
            PasteHelper.FocusWindow(target);
            await Task.Delay(150);
            PasteHelper.CtrlV();
            if (saved != null && settings.PreserveClipboard)
            {
                await Task.Delay(450);
                if (ClipboardSnapshot.Sequence == ours) ClipboardSnapshot.Restore(saved);
            }
            AddHistory(o.Text, 0, "selection");
            StatusLabel.Text = choice.Value.Label + " angewendet ✓ (Strg+Z macht es rückgängig)";
        }
        finally
        {
            overlay?.HideOverlay();
            busy = false;
        }
    }

    private bool StartInstruction()
    {
        if (recorder.IsRecording || MeetingActive) return false;
        instructionRecorder = new AudioRecorder { Settings = settings };
        return instructionRecorder.Start();
    }

    private async Task<string?> StopInstructionAsync()
    {
        var r = instructionRecorder;
        instructionRecorder = null;
        if (r == null) return null;
        var samples = r.Stop();
        if (samples.Length < 4000) return null;
        // Anweisungen nie übersetzen — sie sind für das Sprachmodell
        var text = await Task.Run(() => Transcriber.Transcribe(samples, settings, Lang, translate: false));
        return text.Length > 0 ? text : null;
    }

    // ---------- Umformen des Transkripts im Fenster ----------

    private async void OnTransformTranscript(object? sender, Avalonia.Interactivity.RoutedEventArgs e)
    {
        var text = TranscriptBox.Text?.Trim() ?? "";
        if (busy || text.Length == 0) return;
        if (!Enhancer.IsConfigured(settings))
        {
            StatusLabel.Text = "Umformen braucht ein Sprachmodell — Einstellungen → Enhance.";
            return;
        }
        var win = new TransformWindow(text, StartInstruction, StopInstructionAsync);
        await win.ShowDialog(this);
        var choice = await win.Result;
        if (choice == null) return;
        busy = true;
        ShowWork(cancellable: true);
        StatusLabel.Text = choice.Value.Label + " …";
        enhanceCts = new CancellationTokenSource();
        var lang = TextRules.GuessLanguage(text);
        var o = await Enhancer.TransformAsync(settings, text, choice.Value.Instruction,
            lang.Length > 0 ? lang : Transcriber.DictationLanguage(settings), enhanceCts.Token);
        HideWork();
        busy = false;
        if (o.Text != null)
        {
            TranscriptBox.Text = o.Text;
            StatusLabel.Text = choice.Value.Label + " ✓ — der Verlauf behält das Original";
        }
        else StatusLabel.Text = o.Cancelled ? "Abgebrochen." : "Sprachmodell fehlgeschlagen: " + o.Error;
    }

    private void OnTranslateChanged(object? sender, Avalonia.Interactivity.RoutedEventArgs e)
    {
        if (suppressSettingsEvents) return;
        settings.Translate = TranslateBox.IsChecked == true;
        settings.Save();
    }
}
