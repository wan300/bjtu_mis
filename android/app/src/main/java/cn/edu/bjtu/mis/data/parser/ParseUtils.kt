package cn.edu.bjtu.mis.data.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

fun normalizeSpace(value: String?): String =
    value.orEmpty().replace(Regex("\\s+"), " ").trim()

fun Element.cellText(): String = normalizeSpace(text())

fun tableRows(table: Element): List<List<String>> =
    table.select("tr").mapNotNull { row ->
        val cells = row.select("> th, > td").map { it.cellText() }
        cells.takeIf { values -> values.any { it.isNotBlank() } }
    }

fun headerIndex(headers: List<String>, vararg needles: String): Int? =
    headers.indexOfFirst { header -> needles.any { header.contains(it) } }
        .takeIf { it >= 0 }

fun parseCredit(value: String?): Double? =
    Regex("\\d+(?:\\.\\d+)?").find(value.orEmpty())?.value?.toDoubleOrNull()

fun splitLocation(value: String): Triple<String?, String?, String?> {
    val parts = value.split(",")
        .map(::normalizeSpace)
        .filter { it.isNotBlank() }
    return Triple(parts.getOrNull(0), parts.getOrNull(1), parts.getOrNull(2))
}

fun stripHtmlExcerpt(value: String?, limit: Int = 160): String {
    val text = normalizeSpace(Jsoup.parse(value.orEmpty()).text())
    return if (text.length > limit) text.take(limit).trimEnd() else text
}

fun firstScalar(map: Map<String, Any?>, vararg keys: String): String? =
    keys.asSequence()
        .mapNotNull { map[it] }
        .map { it.toString().trim() }
        .firstOrNull { it.isNotBlank() }

fun cleanId(value: Any?, default: String? = null): String? =
    value?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: default

fun cleanInt(value: Any?): Int? = value?.toString()?.trim()?.toIntOrNull()

fun firstPresent(map: Map<String, Any?>, vararg keys: String): Any? =
    keys.firstNotNullOfOrNull { key -> map[key]?.takeUnless { it.toString().isBlank() } }
