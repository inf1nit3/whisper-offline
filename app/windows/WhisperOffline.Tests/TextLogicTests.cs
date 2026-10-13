using WhisperOffline;
using Xunit;

public class TextLogicTests
{
    [Fact] public void CologneGroupsSpellings()
    {
        Assert.Equal(Phonetics.Cologne("Meier"), Phonetics.Cologne("Maier"));
        Assert.Equal(Phonetics.Cologne("Meier"), Phonetics.Cologne("Mayr"));
        Assert.Equal("65752682", Phonetics.Cologne("Müller-Lüdenscheidt"));
    }

    [Fact] public void FixesSplitAndMisspelledName()
    {
        var w = new[] { "Scheisssewasser" };
        Assert.Equal("Hallo Scheisssewasser, wie geht's?", WordFixer.Correct("Hallo Scheiße Wasser, wie geht's?", w));
        Assert.Equal("Grüße von Scheisssewasser.", WordFixer.Correct("Grüße von Scheißewasser.", w));
        Assert.Equal("Ich nutze Claude Code dafür", WordFixer.Correct("Ich nutze claude code dafür", new[] { "Claude Code" }));
        Assert.Equal("(Umsatzsteuervoranmeldung)", WordFixer.Correct("(Umsatzsteuer Voranmeldung)", new[] { "Umsatzsteuervoranmeldung" }));
    }

    [Fact] public void LeavesUnrelatedWordsAlone()
    {
        const string text = "Klaus stellt den Kasten neben die Kanne.";
        Assert.Equal(text, WordFixer.Correct(text, new[] { "Claude", "Kasse", "Umsatzsteuervoranmeldung" }));
        Assert.Null(WordFixer.Score("Mix", "Max"));
    }

    [Fact] public void Snippets()
    {
        var s = new[] { new Snippet("YouTube Kanal Link", "https://youtube.com/@example") };
        Assert.Equal("https://youtube.com/@example", TextMatching.MatchSnippet("YouTube-Kanal-Link.", s));
        Assert.Null(TextMatching.MatchSnippet("Schick mir den YouTube Kanal Link bitte", s));
    }

    [Fact] public void Replacements()
    {
        var r = new[] { new ReplacementRule("max", "Max Mustermann") };
        Assert.Equal("Hallo Max Mustermann, maximal", TextMatching.ApplyReplacements("Hallo max, maximal", r));
    }

    [Fact] public void SpokenCommands()
    {
        Assert.Equal("Hallo Herr Müller,\n\nvielen Dank für Ihre Nachricht.\nViele Grüße",
            TextRules.ApplySpokenCommands("Hallo Herr Müller Komma. Neuer Absatz. vielen Dank für Ihre Nachricht Punkt neue Zeile Viele Grüße"));
        Assert.Equal("Der Kommandant fragt nach dem Punktestand.", TextRules.ApplySpokenCommands("Der Kommandant fragt nach dem Punktestand."));
    }

    [Fact] public void FillersAndCapitals()
    {
        Assert.Equal("Ich komme um vier.", TextRules.RemoveFillers("Äh, ich komme ähm um vier.", "de"));
        Assert.Equal("Hallo. Wie geht's?\nGut! Am 1. Mai um 3.5 Uhr", TextRules.CapitalizeSentences("hallo. wie geht's?\ngut! am 1. Mai um 3.5 Uhr"));
        Assert.Equal("- [ ] Milch kaufen", TextRules.CapitalizeSentences("- [ ] milch kaufen"));
        Assert.Equal("de", TextRules.GuessLanguage("Ich schicke dir die Unterlagen bis Freitag, danke."));
        Assert.Equal("en", TextRules.GuessLanguage("I will send you the documents by Friday, thanks."));
    }

    [Fact] public void ModesFromProcessAndTitle()
    {
        Assert.Equal(FormatMode.Chat, FormatModes.Detect("WhatsApp.exe", ""));
        Assert.Equal(FormatMode.Email, FormatModes.Detect("OUTLOOK.EXE", "Posteingang"));
        Assert.Equal(FormatMode.Email, FormatModes.Detect("chrome.exe", "Posteingang (3) - Gmail"));
        Assert.Equal(FormatMode.Code, FormatModes.Detect("WindowsTerminal.exe", ""));
        Assert.Equal(FormatMode.Standard, FormatModes.Detect("explorer.exe", ""));
        Assert.Equal(FormatMode.Note, FormatModes.Detect("WhatsApp.exe", "", FormatMode.Note));
    }

    [Fact] public void TurnsAndRegistry()
    {
        var merged = SpeakerMath.MergeTurns(new[]
        {
            new SpeakerMath.Turn(0, 0f, 5f), new SpeakerMath.Turn(0, 5.5f, 9f),
            new SpeakerMath.Turn(1, 9.2f, 12f), new SpeakerMath.Turn(1, 15f, 75f),
        });
        Assert.Equal(new SpeakerMath.Turn(0, 0f, 9f), merged[0]);
        Assert.All(merged.Skip(2), t => Assert.True(t.End - t.Start <= 28f));
        var r = new SpeakerMath.Registry(0.6f, 0);
        Assert.Equal(0, r.Assign(new[] { 1f, 0f, 0f }));
        Assert.Equal(1, r.Assign(new[] { 0f, 1f, 0f }));
        Assert.Equal(0, r.Assign(new[] { 0.9f, 0.1f, 0f }));
    }

    [Fact] public void PausesFound()
    {
        float[] Tone(double s) => Enumerable.Range(0, (int)(s * 16000)).Select(i => (float)(0.3 * Math.Sin(i * 0.1))).ToArray();
        float[] Silence(double s) => new float[(int)(s * 16000)];
        var audio = Tone(2.5).Concat(Silence(0.6)).Concat(Tone(1.0)).ToArray();
        var cut = Pauses.LastPause(audio, 32000);
        Assert.InRange(cut, 40000, (int)(3.1 * 16000));
        Assert.Equal(-1, Pauses.LastPause(Tone(5.0), 32000));
    }

    [Fact] public void StatsSumUp()
    {
        var now = DateTimeOffset.FromUnixTimeMilliseconds(100L * 86_400_000L);
        var e = new[]
        {
            new HistoryEntry(now.ToUnixTimeMilliseconds() - 1000, "eins zwei drei vier", "m", "de", 2f, "dictation"),
            new HistoryEntry(now.ToUnixTimeMilliseconds() - 2 * 86_400_000L, "fünf sechs", "m", "en", 1f, "file"),
            new HistoryEntry(now.ToUnixTimeMilliseconds() - 40 * 86_400_000L, "alt", "m", "de", 1f, "app"),
        };
        var week = Stats.Compute(e, 7, null, now);
        Assert.Equal(2, week.Count);
        Assert.Equal(6, week.Words);
        Assert.Equal(7, week.PerDay.Count);
        Assert.Equal(2, Stats.Compute(e, 0, "dictation", now).Count);
    }

    [Fact] public void SpeechRegionsFound()
    {
        float[] Tone(double s) => Enumerable.Range(0, (int)(s * 16000)).Select(i => (float)(0.3 * Math.Sin(i * 0.1))).ToArray();
        float[] Silence(double s) => new float[(int)(s * 16000)];
        var audio = Silence(1).Concat(Tone(2)).Concat(Silence(0.3)).Concat(Tone(1)).Concat(Silence(2)).Concat(Tone(1.5)).ToArray();
        var r = SpeechRegions.Find(audio);
        Assert.Equal(2, r.Count); // kurze Pause überbrückt, lange trennt
        Assert.InRange(r[0].Start, 0.7f, 1.0f);
        Assert.InRange(r[1].Start, 5.9f, 6.4f);
    }

    [Fact] public void PromptPutsOwnWordsBeforeDefaults()
    {
        var defaults = new HashSet<string> { "deployment", "sprint", "pull request" };
        var p = TextMatching.Prompt(new[] { "Deployment", "Scheisssewasser", "Sprint" }, defaults);
        Assert.True(p.IndexOf("Scheisssewasser") < p.IndexOf("Deployment"));
        Assert.EndsWith(".", p);
        // Relative Reihenfolge bleibt je Gruppe erhalten
        Assert.Equal("Backlog, Meier, Deployment.",
            TextMatching.Prompt(new[] { "Backlog", "Deployment", "Meier" }, defaults));
    }

    [Fact] public void PromptStaysWithinBudget()
    {
        var words = Enumerable.Range(1, 50).Select(i => $"Wort{i} mit Länge").ToList();
        Assert.True(TextMatching.Prompt(words).Length <= 801);
    }

    [Fact] public void VocabularyDefaultsEmbedded()
    {
        // Eingebettete Standardliste (shared/vocabulary.json) muss ladbar sein
        Assert.True(VocabularyDefaults.Words.Count > 50);
        Assert.Contains("Deployment", VocabularyDefaults.Words);
        Assert.Contains(VocabularyDefaults.Lower, w => w == "deployment");
    }
}
