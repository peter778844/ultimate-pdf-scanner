package de.ultimatepdfscanner.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.DateFormat
import java.util.Locale

/**
 * UI language. Texts are written in German in the code and wrapped in [tr]; every other
 * language is a table in assets/i18n/<code>.tsv (German text TAB translation, gettext style),
 * loaded when first needed. Missing entries fall back to German. The choice is a Compose
 * state, so switching updates every screen at once.
 */
object I18n {
    /** Order as offered in the settings; [label] is the language's own name. */
    enum class Choice(val code: String, val label: String, val rtl: Boolean = false) {
        SYSTEM("", "Systemsprache"),
        EN("en", "English"),
        ZH("zh", "中文（简体）"),
        HI("hi", "हिन्दी"),
        AR("ar", "العربية", rtl = true),
        FR("fr", "Français"),
        BN("bn", "বাংলা"),
        PT("pt", "Português"),
        RU("ru", "Русский"),
        ID("id", "Bahasa Indonesia"),
        UR("ur", "اردو", rtl = true),
        DE("de", "Deutsch"),
        JA("ja", "日本語"),
        PNB("pnb", "پنجابی (لہندا)", rtl = true), // Lahnda / Western Punjabi, Shahmukhi script
        // European languages, by their own name
        BG("bg", "Български"),
        BS("bs", "Bosanski"),
        CA("ca", "Català"),
        CS("cs", "Čeština"),
        DA("da", "Dansk"),
        ET("et", "Eesti"),
        EL("el", "Ελληνικά"),
        ES("es", "Español"),
        GA("ga", "Gaeilge"),
        HR("hr", "Hrvatski"),
        IS("is", "Íslenska"),
        IT("it", "Italiano"),
        LV("lv", "Latviešu"),
        LT("lt", "Lietuvių"),
        HU("hu", "Magyar"),
        MK("mk", "Македонски"),
        MT("mt", "Malti"),
        NL("nl", "Nederlands"),
        NB("nb", "Norsk bokmål"),
        PL("pl", "Polski"),
        RO("ro", "Română"),
        SQ("sq", "Shqip"),
        SK("sk", "Slovenčina"),
        SL("sl", "Slovenščina"),
        SR("sr", "Српски"),
        FI("fi", "Suomi"),
        SV("sv", "Svenska"),
        TR("tr", "Türkçe"),
        BE("be", "Беларуская"),
        UK("uk", "Українська"),
    }

    var choice by mutableStateOf(Choice.SYSTEM)

    private var appContext: Context? = null
    private val tables = HashMap<String, Map<String, String>>()

    fun init(context: Context) { appContext = context.applicationContext }

    /** The language in effect: the system language if it is offered, otherwise English. */
    val active: Choice get() {
        if (choice != Choice.SYSTEM) return choice
        val sys = Locale.getDefault().language.let { when (it) { "in" -> "id"; "no" -> "nb"; else -> it } } // old Android: Indonesian "in"; Norwegian may come as "no"
        return Choice.entries.firstOrNull { it.code == sys && it != Choice.SYSTEM } ?: Choice.EN
    }

    val language: String get() = active.code
    val rtl: Boolean get() = active.rtl

    val locale: Locale get() = if (language == "de") Locale.GERMANY else Locale.forLanguageTag(language)

    private fun table(code: String): Map<String, String> = synchronized(tables) {
        tables.getOrPut(code) {
            val ctx = appContext ?: return@getOrPut emptyMap()
            runCatching {
                ctx.assets.open("i18n/$code.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.mapNotNull { l ->
                        val tab = l.indexOf('\t')
                        if (tab <= 0) null else l.substring(0, tab).replace("\\n", "\n") to l.substring(tab + 1).replace("\\n", "\n")
                    }.toMap()
                }
            }.getOrDefault(emptyMap())
        }
    }

    fun lookup(german: String): String = if (language == "de") german else table(language)[german] ?: german

    /** Day and time in the UI language. */
    fun dateTime(): DateFormat =
        if (language == "de") java.text.SimpleDateFormat("dd.MM.yyyy, HH:mm", locale)
        else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
}

/** Translate a German UI text; [args] fill %s / %d placeholders. */
fun tr(german: String, vararg args: Any?): String {
    val t = I18n.lookup(german)
    return if (args.isEmpty()) t else String.format(I18n.locale, t, *args)
}

/** Singular/plural: [one] and [other] are German texts with an optional %d for [n]. */
fun trn(n: Int, one: String, other: String): String = tr(if (n == 1) one else other, n)
