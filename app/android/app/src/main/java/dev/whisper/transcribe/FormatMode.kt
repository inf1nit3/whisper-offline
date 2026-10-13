package dev.whisper.transcribe

import android.content.Context
import org.json.JSONObject

/// Wie Enhance ein Diktat formatiert. AUTO wählt anhand der Ziel-App und des
/// Textfelds (nur über die Diktat-Tastatur bekannt).
enum class FormatMode(val key: String, val label: String) {
    AUTO("auto", "Automatisch"),
    STANDARD("standard", "Standard"),
    EMAIL("email", "E-Mail"),
    CHAT("chat", "Chat"),
    NOTE("note", "Notiz"),
    DOCUMENT("document", "Dokument"),
    CODE("code", "Code"),
    TODO("todo", "Aufgabenliste"),
    /// Betreffzeile — nur automatisch erkannt, nicht wählbar.
    SUBJECT("subject", "Betreff"),
    /// Aufzählung — nur per gesprochener Anweisung („bitte als Liste“).
    BULLETS("bullets", "Liste"),
    /// Keine Nachbearbeitung durch das Sprachmodell.
    RAW("raw", "Roh");

    companion object {
        fun of(key: String?): FormatMode = entries.firstOrNull { it.key == key } ?: AUTO

        /// Zur Auswahl im Overlay und in den App-Profilen.
        val selectable = listOf(AUTO, STANDARD, EMAIL, CHAT, NOTE, DOCUMENT, CODE, TODO, RAW)
    }
}

/// Ermittelt den Modus aus dem Textfeld (EditorInfo) und der App.
/// Reine Logik mit den InputType-Konstanten als Zahlen — unit-getestet.
object ModeDetector {

    private const val MASK_CLASS = 0x0f
    private const val CLASS_TEXT = 1
    private const val CLASS_NUMBER = 2
    private const val MASK_VARIATION = 0xff0
    private const val V_URI = 0x10
    private const val V_EMAIL_ADDRESS = 0x20
    private const val V_EMAIL_SUBJECT = 0x30
    private const val V_SHORT_MESSAGE = 0x40
    private const val V_LONG_MESSAGE = 0x50
    private const val V_PERSON_NAME = 0x60
    private const val V_PASSWORD = 0x80
    private const val V_VISIBLE_PASSWORD = 0x90
    private const val V_FILTER = 0xb0
    private const val V_PHONETIC = 0xc0
    private const val V_WEB_EMAIL_ADDRESS = 0xd0
    private const val V_WEB_PASSWORD = 0xe0
    private const val NUMBER_V_PASSWORD = 0x10

    /// Passwort- und PIN-Felder: dort wird weder diktiert noch etwas gelesen.
    fun isSecure(inputType: Int): Boolean {
        val cls = inputType and MASK_CLASS
        val v = inputType and MASK_VARIATION
        return when (cls) {
            CLASS_TEXT -> v == V_PASSWORD || v == V_VISIBLE_PASSWORD || v == V_WEB_PASSWORD
            CLASS_NUMBER -> v == NUMBER_V_PASSWORD
            else -> false
        }
    }

    private val CHAT_APPS = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thunderdog.challegram",
        "org.thoughtcrime.securesms", "com.discord", "com.Slack", "com.google.android.apps.messaging",
        "com.samsung.android.messaging", "com.facebook.orca", "com.instagram.android", "im.vector.app",
        "io.element.android.x", "com.microsoft.teams", "ch.threema.app", "com.viber.voip", "chat.simplex.app",
    )
    private val MAIL_APPS = setOf(
        "com.google.android.gm", "com.microsoft.office.outlook", "ch.protonmail.android", "me.proton.android.mail",
        "de.gmx.mobile.android.mail", "de.web.mobile.android.mail", "com.fsck.k9", "net.thunderbird.android",
        "com.samsung.android.email.provider", "eu.faircode.email", "com.yahoo.mobile.client.android.mail",
    )
    private val NOTE_APPS = setOf(
        "com.google.android.keep", "com.samsung.android.app.notes", "md.obsidian", "com.microsoft.office.onenote",
        "notion.id", "com.evernote", "net.cozic.joplin", "com.orgzly", "com.simplemobiletools.notes.pro",
    )
    private val DOC_APPS = setOf(
        "com.google.android.apps.docs.editors.docs", "com.microsoft.office.word", "com.microsoft.office.officehubrow",
        "org.documentfoundation.libreoffice", "cn.wps.moffice_eng",
    )
    private val TODO_APPS = setOf(
        "com.todoist", "com.microsoft.todos", "com.ticktick.task", "com.google.android.apps.tasks", "org.tasks",
        "com.anydo",
    )
    private val CODE_APPS = setOf(
        "com.termux", "com.github.android", "com.foxdebug.acode", "com.aide.ui", "dev.jtsalva.cloudmare",
    )

    /// Modus für ein Textfeld. [profile] = in den Einstellungen für die App
    /// festgelegter Modus (hat Vorrang vor der Erkennung).
    fun detect(packageName: String?, inputType: Int, profile: FormatMode? = null): FormatMode {
        val cls = inputType and MASK_CLASS
        val v = inputType and MASK_VARIATION
        if (isSecure(inputType)) return FormatMode.RAW
        // Adressen, Namen, Suchfelder, Zahlen: wörtlich übernehmen
        if (cls == CLASS_NUMBER || cls != CLASS_TEXT && cls != 0) return FormatMode.RAW
        if (v == V_URI || v == V_EMAIL_ADDRESS || v == V_WEB_EMAIL_ADDRESS || v == V_PERSON_NAME ||
            v == V_FILTER || v == V_PHONETIC) return FormatMode.RAW
        if (v == V_EMAIL_SUBJECT) return FormatMode.SUBJECT
        if (profile != null && profile != FormatMode.AUTO) return profile
        val pkg = packageName.orEmpty()
        return when {
            pkg in CHAT_APPS -> FormatMode.CHAT
            pkg in MAIL_APPS -> FormatMode.EMAIL
            pkg in NOTE_APPS -> FormatMode.NOTE
            pkg in DOC_APPS -> FormatMode.DOCUMENT
            pkg in TODO_APPS -> FormatMode.TODO
            pkg in CODE_APPS -> FormatMode.CODE
            v == V_SHORT_MESSAGE -> FormatMode.CHAT
            v == V_LONG_MESSAGE -> FormatMode.EMAIL
            else -> FormatMode.STANDARD
        }
    }
}

/// Einstellungen pro App: fester Modus, Enhance aus (ausgeschlossen) und
/// „nach dem Diktat senden“ (Chat-Apps).
object AppProfiles {

    data class Profile(
        val mode: FormatMode = FormatMode.AUTO,
        /// Ausgeschlossen: rohes Diktat, kein Enhance, keine Kontexterfassung.
        val excluded: Boolean = false,
        /// Nach dem Einfügen automatisch senden (Senden-Taste des Textfelds).
        val autoSend: Boolean = false,
    )

    private const val KEY = "app_profiles"

    @Volatile var all: Map<String, Profile> = emptyMap()
        private set

    fun init(context: Context) {
        val json = Settings.prefs(context).getString(KEY, null) ?: return
        all = runCatching {
            val o = JSONObject(json)
            o.keys().asSequence().associateWith { pkg ->
                val p = o.getJSONObject(pkg)
                Profile(FormatMode.of(p.optString("m")), p.optBoolean("x"), p.optBoolean("s"))
            }
        }.getOrDefault(emptyMap())
    }

    fun get(pkg: String?): Profile? = pkg?.let { all[it] }

    fun set(context: Context, pkg: String, profile: Profile?) {
        val next = all.toMutableMap()
        if (profile == null || profile == Profile()) next.remove(pkg) else next[pkg] = profile
        all = next
        val o = JSONObject()
        next.forEach { (k, p) -> o.put(k, JSONObject().put("m", p.mode.key).put("x", p.excluded).put("s", p.autoSend)) }
        Settings.prefs(context).edit().putString(KEY, o.toString()).apply()
    }
}
