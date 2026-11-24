package com.smartscan.voice

import com.smartscan.data.NodeKind
import com.smartscan.data.TaxonomyNode
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

sealed interface VoiceCommand {
    data object Capture : VoiceCommand
    data object Finish : VoiceCommand
    data object NewDocument : VoiceCommand
    data object DeleteLast : VoiceCommand
    data object ToggleAuto : VoiceCommand
    data object ToggleFlash : VoiceCommand
    data class Metadata(val text: String) : VoiceCommand
    data object Unknown : VoiceCommand
}

fun normalizeText(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9/ ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

/** Hands-free commands understood while scanning (Italian + a few English words). */
object VoiceCommands {
    private fun has(t: String, vararg phrases: String) = phrases.any { Regex("\\b$it\\b").containsMatchIn(t) }

    fun parse(raw: String): VoiceCommand {
        val t = normalizeText(raw)
        if (t.isEmpty()) return VoiceCommand.Unknown
        if (MetadataParser.hasKeyword(t)) return VoiceCommand.Metadata(raw)
        return when {
            has(t, "nuovo documento", "documento successivo", "prossimo documento", "new document") -> VoiceCommand.NewDocument
            has(t, "fine documento", "ho finito", "termina", "fine", "salva", "done", "finish") -> VoiceCommand.Finish
            has(t, "annulla ultima", "cancella ultima", "elimina ultima", "rifai", "undo") -> VoiceCommand.DeleteLast
            has(t, "scatta", "foto", "acquisisci", "pagina successiva", "prossima pagina", "prossima", "avanti", "next", "capture") -> VoiceCommand.Capture
            has(t, "automatico", "auto") -> VoiceCommand.ToggleAuto
            has(t, "flash", "luce", "torcia") -> VoiceCommand.ToggleFlash
            else -> VoiceCommand.Unknown
        }
    }
}

data class ParsedMetadata(
    val title: String? = null,
    val notes: String? = null,
    val date: String? = null,
    val nodeIds: Set<Long> = emptySet(),
    val matchedNames: List<String> = emptyList(),
    val newTags: List<String> = emptyList(),
    val unmatchedCategories: List<String> = emptyList(),
    val createdTagIds: List<Long> = emptyList(),
) {
    fun summary(): String = buildList {
        title?.let { add("Titolo: $it") }
        date?.let { add("Data: $it") }
        if (matchedNames.isNotEmpty()) add("Etichette: " + matchedNames.joinToString(", "))
        if (newTags.isNotEmpty()) add("Nuovi tag: " + newTags.joinToString(", "))
        if (unmatchedCategories.isNotEmpty()) add("Categorie non trovate: " + unmatchedCategories.joinToString(", "))
        notes?.let { add("Nota: $it") }
    }.joinToString(" · ").ifEmpty { "Nessun metadato riconosciuto" }
}

/**
 * Turns a spoken phrase into metadata, e.g.
 * "titolo fattura enel categoria bollette tag urgente e casa nota pagare entro venerdì data 12 marzo 2025".
 * Without keywords, known category/tag names in the phrase are assigned and the rest becomes the title.
 */
object MetadataParser {
    private enum class Field { TITLE, CATEGORY, TAG, NOTE, DATE }

    private val keywords: Map<String, Field> = mapOf(
        "titolo" to Field.TITLE, "nome" to Field.TITLE, "oggetto" to Field.TITLE, "title" to Field.TITLE,
        "categoria" to Field.CATEGORY, "categorie" to Field.CATEGORY, "cartella" to Field.CATEGORY, "category" to Field.CATEGORY,
        "tag" to Field.TAG, "tags" to Field.TAG, "etichetta" to Field.TAG, "etichette" to Field.TAG, "label" to Field.TAG,
        "nota" to Field.NOTE, "note" to Field.NOTE, "descrizione" to Field.NOTE,
        "data" to Field.DATE, "date" to Field.DATE,
    )

    fun hasKeyword(normalized: String): Boolean = normalized.split(' ').any { it in keywords }

    private val listSplit = Regex("\\s+e\\s+|\\s+ed\\s+|,|\\s+virgola\\s+|\\s+poi\\s+|\\s+and\\s+")

    fun parse(raw: String, nodes: List<TaxonomyNode>): ParsedMetadata {
        val text = normalizeText(raw)
        val words = text.split(' ').filter { it.isNotEmpty() }
        val segments = ArrayList<Pair<Field, String>>()
        var current: Field? = null
        val buf = ArrayList<String>()
        val loose = ArrayList<String>()
        fun flush() {
            val v = buf.joinToString(" ").trim()
            if (v.isNotEmpty()) current?.let { segments += it to v } ?: loose.add(v)
            buf.clear()
        }
        for (w in words) {
            val f = keywords[w]
            if (f != null) { flush(); current = f } else buf += w
        }
        flush()

        var title: String? = null
        var notes: String? = null
        var date: String? = null
        val ids = LinkedHashSet<Long>()
        val names = ArrayList<String>()
        val newTags = ArrayList<String>()
        val missingCats = ArrayList<String>()

        // Notes keep the original casing: re-extract them from the raw string when possible.
        for ((field, value) in segments) when (field) {
            Field.TITLE -> title = value.replaceFirstChar { it.uppercase() }
            Field.NOTE -> notes = value.replaceFirstChar { it.uppercase() }
            Field.DATE -> date = parseDate(value) ?: date
            Field.CATEGORY, Field.TAG -> {
                val kind = if (field == Field.CATEGORY) NodeKind.CATEGORY else NodeKind.TAG
                for (item in value.split(listSplit).map { it.trim() }.filter { it.isNotEmpty() }) {
                    val n = match(item, nodes, kind) ?: match(item, nodes, null)
                    when {
                        n != null -> { ids += n.id; names += n.name }
                        kind == NodeKind.TAG -> newTags += item
                        else -> missingCats += item
                    }
                }
            }
        }

        if (loose.isNotEmpty()) {
            val rest = loose.joinToString(" ")
            var remaining = " $rest "
            nodes.filter { normalizeText(it.name).length >= 3 }
                .sortedByDescending { it.name.length }
                .forEach { n ->
                    val nn = " ${normalizeText(n.name)} "
                    if (remaining.contains(nn)) {
                        ids += n.id; names += n.name
                        remaining = remaining.replace(nn, " ")
                    }
                }
            val leftover = remaining.trim().replace(Regex("\\s+"), " ")
            if (title == null && leftover.isNotEmpty()) title = leftover.replaceFirstChar { it.uppercase() }
            if (date == null) date = parseDate(rest)
        }

        return ParsedMetadata(title, notes, date, ids, names.distinct(), newTags.distinct(), missingCats)
    }

    fun match(value: String, nodes: List<TaxonomyNode>, kind: NodeKind?): TaxonomyNode? {
        val v = normalizeText(value)
        if (v.isBlank()) return null
        val cands = nodes.filter { kind == null || it.kind == kind }
        cands.firstOrNull { normalizeText(it.name) == v }?.let { return it }
        var best: TaxonomyNode? = null
        var bestScore = 0.0
        for (n in cands) {
            val s = similarity(normalizeText(n.name), v)
            if (s > bestScore) { bestScore = s; best = n }
        }
        return if (bestScore >= 0.78) best else null
    }

    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val m = max(a.length, b.length)
        if (m == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / m
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private val months = listOf(
        "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
        "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"
    )

    fun parseDate(s: String, today: LocalDate = LocalDate.now()): String? {
        val t = normalizeText(s)
        val iso = DateTimeFormatter.ISO_LOCAL_DATE
        if (Regex("\\boggi\\b").containsMatchIn(t)) return today.format(iso)
        if (Regex("\\bieri\\b").containsMatchIn(t)) return today.minusDays(1).format(iso)
        Regex("\\b(\\d{1,2})[ /.-](\\d{1,2})[ /.-](\\d{2,4})\\b").find(t)?.let { m ->
            val (d, mo, y) = m.destructured
            return build(d.toInt(), mo.toInt(), y.toInt())?.format(iso)
        }
        Regex("\\b(\\d{1,2}|primo) (${months.joinToString("|")})(?: (\\d{4}))?\\b").find(t)?.let { m ->
            val d = if (m.groupValues[1] == "primo") 1 else m.groupValues[1].toInt()
            val mo = months.indexOf(m.groupValues[2]) + 1
            val y = m.groupValues[3].toIntOrNull() ?: today.year
            return build(d, mo, y)?.format(iso)
        }
        return null
    }

    private fun build(d: Int, m: Int, y: Int): LocalDate? {
        val year = if (y < 100) 2000 + y else y
        return runCatching { LocalDate.of(year, m, d) }.getOrNull()
    }
}
