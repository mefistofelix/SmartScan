package com.smartscan.data

import android.content.Context

enum class ExportQuality(val label: String, val maxSide: Int, val jpegQuality: Int) {
    HIGH("Alta (~300 dpi)", 3300, 88),
    MEDIUM("Media (~200 dpi)", 2340, 75),
    LOW("Compatta (~140 dpi)", 1650, 60),
}

enum class PdfPageSize(val label: String) { AUTO("Adatta all'immagine"), A4("A4"), LETTER("Letter") }

class AppSettings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private inline fun <reified E : Enum<E>> enumPref(key: String, def: E): E =
        p.getString(key, null)?.let { v -> runCatching { enumValueOf<E>(v) }.getOrNull() } ?: def

    private fun bool(key: String, def: Boolean) = p.getBoolean(key, def)
    private fun setBool(key: String, v: Boolean) = p.edit().putBoolean(key, v).apply()

    var defaultFilter: PageFilter
        get() = enumPref("filter", PageFilter.ENHANCED)
        set(v) = p.edit().putString("filter", v.name).apply()
    var quality: ExportQuality
        get() = enumPref("quality", ExportQuality.MEDIUM)
        set(v) = p.edit().putString("quality", v.name).apply()
    var pageSize: PdfPageSize
        get() = enumPref("pageSize", PdfPageSize.AUTO)
        set(v) = p.edit().putString("pageSize", v.name).apply()

    var autoCapture: Boolean
        get() = bool("autoCapture", true); set(v) = setBool("autoCapture", v)
    var multiPage: Boolean
        get() = bool("multiPage", true); set(v) = setBool("multiPage", v)
    var autoDeskew: Boolean
        get() = bool("autoDeskew", true); set(v) = setBool("autoDeskew", v)
    var voiceCommands: Boolean
        get() = bool("voiceCommands", false); set(v) = setBool("voiceCommands", v)
    var voiceMetaPre: Boolean
        get() = bool("voiceMetaPre", false); set(v) = setBool("voiceMetaPre", v)
    var voiceMetaPost: Boolean
        get() = bool("voiceMetaPost", false); set(v) = setBool("voiceMetaPost", v)
    var createUnknownTags: Boolean
        get() = bool("createUnknownTags", true); set(v) = setBool("createUnknownTags", v)
    var wifiOnly: Boolean
        get() = bool("wifiOnly", false); set(v) = setBool("wifiOnly", v)
    var shutterSound: Boolean
        get() = bool("shutterSound", true); set(v) = setBool("shutterSound", v)

    var voiceLanguage: String
        get() = p.getString("voiceLanguage", "it-IT") ?: "it-IT"
        set(v) = p.edit().putString("voiceLanguage", v).apply()

    val wantsVoice: Boolean get() = voiceCommands || voiceMetaPre || voiceMetaPost
}
