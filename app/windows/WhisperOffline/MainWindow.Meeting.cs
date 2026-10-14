using System.IO;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Platform.Storage;
using Avalonia.Threading;

namespace WhisperOffline;

// Meeting-Modus im Hauptfenster. Teil von MainWindow.
public partial class MainWindow
{
    private MeetingRecorder? meetingRecorder;
    private string? meetingId;
    private bool meetingPaused;
    private LiveTranscriber? meetingLive;
    private string meetingLiveText = "";
    private CancellationTokenSource? meetingCts;
    private bool meetingProcessing;
    private string meetingProgress = "";
    private DispatcherTimer? meetingTimer;

    /// Läuft gerade eine Meeting-Aufnahme oder -Auswertung? Dann ruht das Diktat.
    private bool MeetingActive => meetingRecorder != null || meetingPaused || meetingProcessing;

    private void OnOpenMeeting(object? sender, RoutedEventArgs e) => ShowMeeting();

    /// Unfertige Aufnahmen (Absturz, Neustart) beim Start melden.
    private void OfferMeetingRecovery()
    {
        var open = MeetingStore.List().Where(m => m.State != "done").ToList();
        if (open.Count > 0)
            StatusLabel.Text = $"{open.Count} Meeting-Aufnahme(n) noch nicht ausgewertet — unter „Meeting“ fortsetzen.";
    }

    private void ShowMeeting()
    {
        if (meetingRecorder != null || meetingPaused) { ShowMeetingRecording(); return; }
        if (meetingProcessing) { ShowMeetingProcessing(); return; }

        var p = new StackPanel { Spacing = 10, Margin = new Thickness(0, 0, 12, 12) };
        var title = new TextBox { Watermark = "Titel (optional)", MinWidth = 360 };
        var count = new ComboBox { MinWidth = 140, CornerRadius = new CornerRadius(14) };
        count.Items.Add("automatisch");
        for (int i = 2; i <= 8; i++) count.Items.Add(i.ToString());
        count.SelectedIndex = 0;
        var source = new ComboBox
        {
            Items = { "Mikrofon + Systemaudio (Call)", "Nur Mikrofon (Raum)", "Nur Systemaudio" },
            CornerRadius = new CornerRadius(14),
        };
        source.SelectedIndex = settings.MeetingSource switch { "mic" => 1, "system" => 2, _ => 0 };
        var start = new Button { Content = "🔴  Aufnahme starten", Classes = { "primaryAction" }, HorizontalAlignment = HorizontalAlignment.Left };
        var error = new TextBlock { Foreground = ErrorBrush, TextWrapping = TextWrapping.Wrap };
        start.Click += (_, _) =>
        {
            if (!File.Exists(WhisperCli.SelectedModel)) { error.Text = "Erst ein Modell laden."; return; }
            if (dictating || recorder.IsRecording) { error.Text = "Erst das laufende Diktat bzw. die Aufnahme beenden."; return; }
            settings.MeetingSource = source.SelectedIndex switch { 1 => "mic", 2 => "system", _ => "both" };
            settings.Save();
            var now = DateTimeOffset.Now;
            var m = new Meeting
            {
                Id = "m" + now.ToUnixTimeMilliseconds(),
                Title = string.IsNullOrWhiteSpace(title.Text) ? "Meeting " + now.ToString("dd.MM.yyyy HH:mm") : title.Text.Trim(),
                StartedMs = now.ToUnixTimeMilliseconds(),
                SpeakerCount = count.SelectedIndex == 0 ? 0 : count.SelectedIndex + 1,
                Source = settings.MeetingSource,
            };
            MeetingStore.Save(m);
            meetingId = m.Id;
            if (!StartMeetingRecorder(m))
            {
                MeetingStore.Delete(m.Id);
                meetingId = null;
                error.Text = "Aufnahme konnte nicht gestartet werden (Mikrofon belegt?).";
                return;
            }
            ShowMeetingRecording();
        };
        p.Children.Add(Card(new StackPanel
        {
            Spacing = 12,
            Children =
            {
                title,
                new WrapPanel
                {
                    ItemSpacing = 8, LineSpacing = 8,
                    Children =
                    {
                        new TextBlock { Text = "Quelle:", VerticalAlignment = VerticalAlignment.Center, FontWeight = FontWeight.Medium }, source,
                        new TextBlock { Text = "Sprecher:", VerticalAlignment = VerticalAlignment.Center, FontWeight = FontWeight.Medium, Margin = new Thickness(12, 0, 0, 0) }, count,
                    },
                },
                Hint("Bei Calls Kopfhörer benutzen — sonst landet die Gegenseite zusätzlich im Mikrofon. Mit „Mikrofon + Systemaudio“ wirst du als „Ich“ geführt, die Gegenseite wird nach Sprechern getrennt."),
                Diarizer.Available ? new TextBlock { Text = "✓ Sprechererkennung bereit", FontSize = 12, Foreground = TertiaryBrush } : new StackPanel
                {
                    Spacing = 6,
                    Children =
                    {
                        Hint("Ohne Sprechererkennung werden die Sprecher nicht getrennt."),
                        new Button { Content = "Sprechererkennung laden (28 MB) …", Classes = { "chip" } }.With(b => b.Click += (_, _) => { panelBack = ShowMeeting; ShowMeetingModels(); }),
                    },
                },
                start, error,
            },
        }));

        var meetings = MeetingStore.List();
        if (meetings.Count > 0) p.Children.Add(Section("Bisherige Meetings"));
        foreach (var m in meetings)
        {
            var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            if (m.State == "done")
            {
                var open = new Button { Content = "Öffnen", Classes = { "chip" } };
                open.Click += (_, _) => { panelBack = ShowMeeting; ShowMeetingResult(m.Id); };
                row.Children.Add(open);
            }
            else
            {
                var proc = new Button { Content = "Auswerten", Classes = { "primaryAction" } };
                proc.Click += (_, _) => StartMeetingProcessing(m.Id);
                row.Children.Add(proc);
            }
            var del = new Button { Content = "✕", Classes = { "chip" }, Foreground = ErrorBrush };
            del.Click += async (_, _) =>
            {
                if (await Confirm("Meeting löschen?", $"„{m.Title}“ wird mit Aufnahme und Transkript gelöscht.")) { MeetingStore.Delete(m.Id); ShowMeeting(); }
            };
            row.Children.Add(del);
            p.Children.Add(Card(new DockPanel
            {
                Children =
                {
                    row.With(r => DockPanel.SetDock(r, Dock.Right)),
                    new StackPanel
                    {
                        Spacing = 2,
                        Children =
                        {
                            new TextBlock { Text = m.Title, FontWeight = FontWeight.SemiBold },
                            Hint(string.Join(" · ", new[]
                            {
                                DateTimeOffset.FromUnixTimeMilliseconds(m.StartedMs).LocalDateTime.ToString("dd.MM.yyyy HH:mm"),
                                m.DurationSec > 0 ? TimeSpan.FromSeconds(m.DurationSec).ToString(@"h\:mm\:ss") : "",
                                m.Diarized ? $"{m.Speakers.Count} Sprecher" : "",
                                m.State != "done" ? "noch nicht ausgewertet" : "",
                            }.Where(t => t.Length > 0))),
                        },
                    },
                },
            }));
        }
        ShowPanel("Meeting", "Gespräche aufnehmen und nach Sprechern getrennt transkribieren — komplett lokal", Scroll(p));
    }

    private bool StartMeetingRecorder(Meeting m)
    {
        var rec = new MeetingRecorder(m.Id, m.Source, settings);
        if (!rec.Start()) return false;
        meetingRecorder = rec;
        meetingPaused = false;
        meetingLiveText = "";
        if (settings.LiveTranscription && WhisperNative.Available)
        {
            meetingLive = new LiveTranscriber(rec, settings, Lang, false, t => meetingLiveText = t);
            meetingLive.Start();
        }
        return true;
    }

    private void ShowMeetingRecording()
    {
        var clock = new TextBlock { FontSize = 42, FontWeight = FontWeight.Bold, HorizontalAlignment = HorizontalAlignment.Center };
        var level = new ProgressBar { Minimum = 0, Maximum = 1, Height = 6, Width = 320, Foreground = ErrorBrush };
        var live = new TextBlock { TextWrapping = TextWrapping.Wrap, FontSize = 13 };
        var state = new TextBlock { HorizontalAlignment = HorizontalAlignment.Center, FontWeight = FontWeight.Medium };
        var pause = new Button { Classes = { "chip" } };
        var finish = new Button { Content = "⏹  Beenden & auswerten", Classes = { "primaryAction" } };
        var discard = new Button { Content = "Verwerfen", Classes = { "chip" }, Foreground = ErrorBrush };
        void Refresh()
        {
            pause.Content = meetingPaused ? "▶  Fortsetzen" : "⏸  Pause";
            state.Text = meetingPaused ? "Pausiert" : "Aufnahme läuft";
            clock.Foreground = meetingPaused ? SubtleBrush : ErrorBrush;
        }
        pause.Click += (_, _) =>
        {
            if (meetingPaused)
            {
                var m = MeetingStore.Load(meetingId!);
                if (m != null && StartMeetingRecorder(m)) { }
                else { state.Text = "Fortsetzen fehlgeschlagen"; return; }
            }
            else
            {
                meetingLive?.Stop(); meetingLive = null;
                meetingRecorder?.Stop(); meetingRecorder = null;
                meetingPaused = true;
            }
            Refresh();
        };
        finish.Click += (_, _) =>
        {
            meetingLive?.Stop(); meetingLive = null;
            meetingRecorder?.Stop(); meetingRecorder = null;
            meetingPaused = false;
            var m = MeetingStore.Load(meetingId!);
            if (m != null) { m.State = "recorded"; MeetingStore.Save(m); }
            StartMeetingProcessing(meetingId!);
        };
        discard.Click += async (_, _) =>
        {
            if (!await Confirm("Meeting verwerfen?", "Die Aufnahme wird gelöscht und lässt sich nicht wiederherstellen.", "Verwerfen")) return;
            meetingLive?.Stop(); meetingLive = null;
            meetingRecorder?.Stop(); meetingRecorder = null;
            meetingPaused = false;
            meetingTimer?.Stop();
            MeetingStore.Delete(meetingId!);
            meetingId = null;
            ShowMeeting();
        };
        Refresh();
        meetingTimer?.Stop();
        int pausedAt = 0;
        meetingTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(200) };
        meetingTimer.Tick += (_, _) =>
        {
            if (meetingRecorder != null) { pausedAt = meetingRecorder.Seconds; level.Value = meetingRecorder.Level; }
            else level.Value = 0;
            clock.Text = TimeSpan.FromSeconds(pausedAt).ToString(@"h\:mm\:ss");
            live.Text = meetingLiveText.Length > 1500 ? "…" + meetingLiveText[^1500..] : meetingLiveText;
        };
        meetingTimer.Start();
        ShowPanel("Meeting", MeetingStore.Load(meetingId!)?.Title, new StackPanel
        {
            Spacing = 14,
            Children =
            {
                Card(new StackPanel
                {
                    Spacing = 12, HorizontalAlignment = HorizontalAlignment.Stretch,
                    Children =
                    {
                        clock, state, level.With(l => l.HorizontalAlignment = HorizontalAlignment.Center),
                        new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, HorizontalAlignment = HorizontalAlignment.Center, Children = { pause, finish, discard } },
                        Hint("Diktat-Hotkeys ruhen während der Aufnahme. Das Fenster darf in den Infobereich — die Aufnahme läuft weiter.").With(h => h.HorizontalAlignment = HorizontalAlignment.Center),
                    },
                }),
                Card(Scroll(live).With(s => s.MaxHeight = 260)),
            },
        });
    }

    private void StartMeetingProcessing(string id)
    {
        meetingTimer?.Stop();
        meetingId = id;
        meetingProcessing = true;
        meetingProgress = "Bereite vor …";
        meetingCts = new CancellationTokenSource();
        ShowMeetingProcessing();
        _ = Task.Run(async () =>
        {
            string? error = null;
            try
            {
                await MeetingProcessor.RunAsync(settings, id, (p, d) => Dispatcher.UIThread.Post(() =>
                {
                    meetingProgress = d;
                    meetingProgressValue = p;
                }), meetingCts.Token);
            }
            catch (OperationCanceledException) { error = "Abgebrochen — die Aufnahme bleibt erhalten und kann später ausgewertet werden."; }
            catch (Exception ex) { error = "Auswertung fehlgeschlagen: " + ex.Message; }
            Dispatcher.UIThread.Post(() =>
            {
                meetingProcessing = false;
                meetingTimer?.Stop();
                if (error != null) { ShowMeeting(); StatusLabel.Text = error; }
                else { panelBack = ShowMeeting; ShowMeetingResult(id); }
            });
        });
    }

    private float? meetingProgressValue;

    private void ShowMeetingProcessing()
    {
        var bar = new ProgressBar { Minimum = 0, Maximum = 1, Height = 8, MinWidth = 420 };
        var detail = new TextBlock { HorizontalAlignment = HorizontalAlignment.Center, FontWeight = FontWeight.Medium };
        var cancel = new Button { Content = "Abbrechen", Classes = { "chip" }, HorizontalAlignment = HorizontalAlignment.Center };
        cancel.Click += (_, _) => { meetingCts?.Cancel(); WhisperNative.Cancel(); };
        meetingTimer?.Stop();
        meetingTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(250) };
        meetingTimer.Tick += (_, _) =>
        {
            detail.Text = meetingProgress;
            bar.IsIndeterminate = meetingProgressValue == null;
            if (meetingProgressValue is { } v) bar.Value = v;
        };
        meetingTimer.Start();
        ShowPanel("Meeting wird ausgewertet", "Sprechererkennung und Transkription laufen lokal", Card(new StackPanel
        {
            Spacing = 14, Margin = new Thickness(0, 16),
            Children = { bar.With(b => b.HorizontalAlignment = HorizontalAlignment.Center), detail, cancel },
        }));
    }

    private void ShowMeetingResult(string id, string query = "", bool notesTab = false)
    {
        var m = MeetingStore.Load(id);
        if (m == null) { ShowMeeting(); return; }
        var talk = m.TalkTime();
        float total = Math.Max(1, talk.Values.Sum());

        var speakers = new WrapPanel { ItemSpacing = 6, LineSpacing = 6 };
        if (m.Diarized)
            for (int i = 0; i < m.Speakers.Count; i++)
            {
                int idx = i;
                var b = new Button
                {
                    Content = (m.Speakers[i].Recognized ? "✓ " : "👤 ") + $"{m.Speakers[i].Name} · {(talk.GetValueOrDefault(i) / total * 100):F0} %",
                    Classes = { "chip" },
                };
                ToolTip.SetTip(b, "Umbenennen oder zusammenführen");
                b.Click += async (_, _) => { await RenameSpeaker(m, idx); ShowMeetingResult(id, query, notesTab); };
                speakers.Children.Add(b);
            }

        var search = new TextBox { Text = query, Watermark = "Transkript durchsuchen …", MinWidth = 260 };
        search.KeyUp += (_, ev) => { if (ev.Key == Avalonia.Input.Key.Enter) ShowMeetingResult(id, search.Text?.Trim() ?? "", false); };
        var transcript = new StackPanel { Spacing = 10 };
        foreach (var u in m.Utterances.Where(u => query.Length == 0 || u.Text.Contains(query, StringComparison.OrdinalIgnoreCase)))
            transcript.Children.Add(Card(new StackPanel
            {
                Spacing = 4,
                Children =
                {
                    new TextBlock { Text = (m.Diarized ? m.SpeakerName(u.Speaker) + " · " : "") + TimeSpan.FromSeconds(u.Start).ToString(@"h\:mm\:ss"),
                        Foreground = PrimaryBrush, FontSize = 12, FontWeight = FontWeight.SemiBold },
                    new SelectableTextBlock { Text = u.Text, TextWrapping = TextWrapping.Wrap },
                },
            }));
        if (transcript.Children.Count == 0) transcript.Children.Add(Card(Hint("Keine Treffer.")));

        var notesBox = new StackPanel { Spacing = 10 };
        var notesStatus = Hint("");
        var gen = new Button { Content = m.Notes.Length > 0 ? "✨ Neu erstellen" : "✨ Notizen erstellen", Classes = { "primaryAction" }, IsEnabled = Enhancer.IsConfigured(settings) };
        gen.Click += async (_, _) =>
        {
            gen.IsEnabled = false;
            notesStatus.Text = "Meeting wird ausgewertet …";
            var lang = TextRules.GuessLanguage(m.TranscriptText());
            var o = await Enhancer.MeetingNotesAsync(settings, m.TranscriptText(), lang.Length > 0 ? lang : "de", CancellationToken.None,
                d => Dispatcher.UIThread.Post(() => notesStatus.Text = d));
            if (o.Text != null) { m.Notes = o.Text; MeetingStore.Save(m); ShowMeetingResult(id, "", true); }
            else { notesStatus.Text = "Fehlgeschlagen: " + o.Error; gen.IsEnabled = true; }
        };
        if (m.Notes.Length > 0) notesBox.Children.Add(Card(new SelectableTextBlock { Text = m.Notes, TextWrapping = TextWrapping.Wrap }));
        else notesBox.Children.Add(Card(Hint(Enhancer.IsConfigured(settings)
            ? "Macht aus dem Transkript eine Zusammenfassung mit Entscheidungen, Aufgaben und offenen Fragen."
            : "Für Notizen braucht es ein Sprachmodell (Einstellungen → Enhance).")));
        notesBox.Children.Add(gen);
        notesBox.Children.Add(notesStatus);

        var tabs = new TabControl { SelectedIndex = notesTab ? 1 : 0 };
        tabs.Items.Add(new TabItem
        {
            Header = "Transkript",
            Content = new DockPanel
            {
                Children =
                {
                    search.With(s => { DockPanel.SetDock(s, Dock.Top); s.Margin = new Thickness(0, 8, 0, 8); }),
                    Scroll(transcript),
                },
            },
        });
        tabs.Items.Add(new TabItem { Header = "Notizen", Content = Scroll(notesBox.With(n => n.Margin = new Thickness(0, 8, 12, 8))) });

        var copy = new Button { Content = "📋 Kopieren", Classes = { "chip" } };
        copy.Click += async (_, _) => { if (Clipboard != null) await Clipboard.SetTextAsync(MeetingText(m)); };
        var md = new Button { Content = "Markdown …", Classes = { "chip" } };
        md.Click += async (_, _) => await ExportMeeting(m, "md");
        var pdf = new Button { Content = "PDF …", Classes = { "chip" } };
        pdf.Click += async (_, _) => await ExportMeeting(m, "pdf");
        var folder = new Button { Content = "📂 Ordner", Classes = { "chip" } };
        folder.Click += (_, _) => OpenExternalPath(MeetingStore.Folder(id));

        ShowPanel(m.Title, DateTimeOffset.FromUnixTimeMilliseconds(m.StartedMs).LocalDateTime.ToString("dd.MM.yyyy HH:mm") +
                  " · " + TimeSpan.FromSeconds(m.DurationSec).ToString(@"h\:mm\:ss"),
            new DockPanel
            {
                Children =
                {
                    new StackPanel { Spacing = 4, Children = { speakers, m.Diarized ? Hint("Klick auf einen Sprecher: umbenennen, Stimme merken oder zusammenführen. ✓ = an gespeicherter Stimme erkannt.") : Hint("") } }
                        .With(s => DockPanel.SetDock(s, Dock.Top)),
                    tabs,
                },
            },
            copy, md, pdf, folder);
    }

    private static string MeetingText(Meeting m) =>
        $"# {m.Title}\n{DateTimeOffset.FromUnixTimeMilliseconds(m.StartedMs).LocalDateTime:dd.MM.yyyy HH:mm} · {TimeSpan.FromSeconds(m.DurationSec):h\\:mm\\:ss}\n\n" +
        (m.Notes.Length > 0 ? m.Notes.Trim() + "\n\n---\n\n" : "") + m.TranscriptText();

    private async Task ExportMeeting(Meeting m, string ext)
    {
        var safe = new string(m.Title.Where(c => char.IsLetterOrDigit(c) || c is ' ' or '-' or '_').ToArray()).Trim();
        var f = await StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions
        {
            SuggestedFileName = (safe.Length > 0 ? safe : "Meeting") + "." + ext, DefaultExtension = ext,
        });
        var path = f?.TryGetLocalPath();
        if (path == null) return;
        try
        {
            if (ext == "pdf") PdfWriter.Write(path, m.Title, MeetingText(m).Split('\n', 2)[1]);
            else File.WriteAllText(path, MeetingText(m));
            StatusLabel.Text = "Gespeichert: " + path;
        }
        catch (Exception ex) { StatusLabel.Text = "Speichern fehlgeschlagen: " + ex.Message; }
    }

    private static void OpenExternalPath(string path)
    {
        try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(path) { UseShellExecute = true }); } catch { }
    }

    private async Task RenameSpeaker(Meeting m, int idx)
    {
        var name = new TextBox { Text = m.SpeakerName(idx).StartsWith("Sprecher ") ? "" : m.SpeakerName(idx), Watermark = "Name" };
        var remember = new CheckBox { Content = "Stimme merken — in künftigen Meetings automatisch zuordnen", IsChecked = idx != 0 || m.SpeakerName(0) != "Ich" };
        var w = new Window
        {
            Title = "Sprecher benennen", Width = 440, SizeToContent = SizeToContent.Height,
            WindowStartupLocation = WindowStartupLocation.CenterOwner, CanResize = false,
            Background = BrandBrush("BrandSurfaceBrush", Brushes.White),
            Icon = AppIcon.Get(),
        };
        var save = new Button { Content = "Speichern", Classes = { "primaryAction" }, IsDefault = true };
        var merges = new WrapPanel { ItemSpacing = 6, LineSpacing = 6 };
        save.Click += (_, _) =>
        {
            var n = name.Text?.Trim() ?? "";
            if (n.Length == 0) return;
            m.Speakers[idx] = new SpeakerInfo(n);
            MeetingStore.Save(m);
            if (remember.IsChecked == true)
            {
                var v = MeetingStore.LoadVoices(m.Id);
                if (idx < v.Count && v[idx].Length > 0) VoiceProfiles.Save(n, v[idx]);
            }
            w.Close();
        };
        for (int i = 0; i < m.Speakers.Count; i++)
        {
            if (i == idx) continue;
            int into = i;
            var b = new Button { Content = m.Speakers[i].Name, Classes = { "chip" } };
            b.Click += (_, _) =>
            {
                m.Utterances = m.Utterances.Select(u => u.Speaker == idx ? u with { Speaker = into } : u)
                    .Select(u => u.Speaker > idx ? u with { Speaker = u.Speaker - 1 } : u).ToList();
                m.Speakers.RemoveAt(idx);
                var v = MeetingStore.LoadVoices(m.Id);
                if (idx < v.Count) { v.RemoveAt(idx); MeetingStore.SaveVoices(m.Id, v); }
                m.Diarized = m.Speakers.Count > 1;
                MeetingStore.Save(m);
                w.Close();
            };
            merges.Children.Add(b);
        }
        w.Content = new Border
        {
            Margin = new Thickness(16),
            Classes = { "card" },
            Child = new StackPanel
            {
                Spacing = 12,
                Children =
                {
                    name, remember,
                    m.Speakers.Count > 1 ? Hint("Oder zusammenführen mit:") : new TextBlock { IsVisible = false },
                    merges,
                    save.With(s => s.HorizontalAlignment = HorizontalAlignment.Right),
                },
            },
        };
        await w.ShowDialog(this);
    }

    // ---------- Zusatzmodelle für die Sprechertrennung ----------

    private async void ShowMeetingModels()
    {
        var p = new StackPanel { Spacing = 10 };
        var status = Hint("Frage Server …");
        p.Children.Add(Hint("Zwei kleine Modelle (zusammen ca. 28 MB). Danach trennt der Meeting-Modus die Sprecher — komplett offline."));
        p.Children.Add(status);
        ShowPanel("Sprechererkennung", null, Scroll(p));
        List<ModelInfo> list;
        try { list = (await ModelRegistry.FetchCatalog()).Meeting; }
        catch (Exception ex) { status.Text = "Server nicht erreichbar: " + ex.Message; return; }
        foreach (var info in list)
        {
            bool have = File.Exists(Path.Combine(Diarizer.ModelDir, info.File));
            p.Children.Add(Card(new StackPanel
            {
                Spacing = 4,
                Children =
                {
                    new TextBlock { Text = $"{info.Label} ({info.SizeText})", FontWeight = FontWeight.SemiBold },
                    Hint(info.Tagline),
                    have ? new TextBlock { Text = "✓ geladen", FontSize = 12, Foreground = TertiaryBrush } : new TextBlock { IsVisible = false },
                },
            }));
        }
        var missing = list.Where(i => !File.Exists(Path.Combine(Diarizer.ModelDir, i.File))).ToList();
        if (missing.Count == 0) { status.Text = "✓ Bereit."; return; }
        status.Text = "";
        var bar = new ProgressBar { Minimum = 0, Maximum = 1, Width = 300, IsVisible = false };
        var dl = new Button { Content = "⬇  Alle laden", Classes = { "primaryAction" } };
        dl.Click += async (_, _) =>
        {
            dl.IsEnabled = false; bar.IsVisible = true;
            try
            {
                for (int i = 0; i < missing.Count; i++)
                {
                    int k = i;
                    await ModelRegistry.DownloadTo(missing[i], Diarizer.ModelDir, new Progress<double>(v => bar.Value = (k + v) / missing.Count));
                }
                status.Text = "✓ Bereit — Meetings werden jetzt nach Sprechern getrennt.";
            }
            catch (Exception ex) { status.Text = "Download fehlgeschlagen: " + ex.Message; dl.IsEnabled = true; }
        };
        p.Children.Add(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, Children = { dl, bar } });
    }
}
