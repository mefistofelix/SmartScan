package com.smartscan.data

import com.smartscan.R
import com.smartscan.str

import android.content.Context

enum class ExportQuality(private val res: Int, val maxSide: Int, val jpegQuality: Int) {
    HIGH(R.string.quality_high, 3300, 88),
    MEDIUM(R.string.quality_medium, 2340, 75),
    LOW(R.string.quality_low, 1650, 60);

    val label: String get() = str(res)
}

enum class PdfPageSize(private val res: Int) {
    AUTO(R.string.page_auto), A4(R.string.page_a4), LETTER(R.string.page_letter);

    val label: String get() = str(res)
}

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
    var autoDeskew: Boolean
        get() = bool("autoDeskew", true); set(v) = setBool("autoDeskew", v)
    /** Live edge detection in the scanner. Off: the whole frame is the page, manual shutter only. */
    var edgeDetect: Boolean
        get() = bool("edgeDetect", true); set(v) = setBool("edgeDetect", v)
    /** Maximum quality: full sensor resolution, low JPEG compression, no downscaling on export. */
    var maxQuality: Boolean
        get() = bool("maxQuality", false); set(v) = setBool("maxQuality", v)
    /** Save the capture position with each page (EXIF GPS, PDF and upload metadata). */
    var geoTag: Boolean
        get() = bool("geoTag", false); set(v) = setBool("geoTag", v)
    /** Scanner preview: full screen (true) or the whole 4:3 frame, centered (false). */
    var fullPreview: Boolean
        get() = bool("fullPreview", true); set(v) = setBool("fullPreview", v)
    /** Scanner light left on: restored when the scanner opens again or the app returns to the foreground. */
    var torchOn: Boolean
        get() = bool("torchOn", false); set(v) = setBool("torchOn", v)

    /** Draft the scanner is filling: it survives closing the scanner or the app and is resumed next time (0 = none). */
    var scannerDraftId: Long
        get() = p.getLong("scannerDraftId", 0L); set(v) = p.edit().putLong("scannerDraftId", v).apply()
    /** Scanner: documents finished with «Fine» go to the auto-upload endpoints (false = keep local). */
    var uploadOnFinish: Boolean
        get() = bool("uploadOnFinish", true); set(v) = setBool("uploadOnFinish", v)
    /** Open the camera right away when the app starts (the archive stays below it). */
    var startInScanner: Boolean
        get() = bool("startInScanner", true); set(v) = setBool("startInScanner", v)
    /** Continuous voice commands were removed from the UI (only dictated notes remain); kept off. */
    var voiceCommands: Boolean
        get() = false; set(_) = Unit
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
