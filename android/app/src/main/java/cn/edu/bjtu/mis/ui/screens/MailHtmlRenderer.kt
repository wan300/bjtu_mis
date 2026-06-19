package cn.edu.bjtu.mis.ui.screens

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal enum class MailBodyRenderMode {
    Mobile,
    Original,
}

internal fun mailBodyHtml(rawHtml: String, mode: MailBodyRenderMode): String =
    when (mode) {
        MailBodyRenderMode.Mobile -> adaptMailHtmlForMobile(rawHtml)
        MailBodyRenderMode.Original -> rawHtml.takeIf { it.isNotBlank() } ?: plainTextMailDocument(extractMailPlainText(rawHtml))
    }

internal fun adaptMailHtmlForMobile(rawHtml: String): String {
    val trimmed = rawHtml.trim()
    if (trimmed.isBlank()) {
        return plainTextMailDocument(extractMailPlainText(rawHtml))
    }
    return runCatching {
        val document = parseMailDocument(trimmed)
        document.outputSettings().prettyPrint(false)
        document.select("script").remove()
        document.body().select("[style]").forEach(::relaxInlineLayoutStyle)
        document.head().select("meta[name=viewport]").remove()
        document.head()
            .appendElement("meta")
            .attr("name", "viewport")
            .attr("content", MOBILE_VIEWPORT)
        document.head()
            .appendElement("style")
            .attr("data-mail-mobile", "true")
            .appendText(MOBILE_MAIL_STYLE)
        document.body().addClass("mail-mobile-body")
        val hasVisibleBody = document.body().text().isNotBlank() || document.body().children().isNotEmpty()
        if (hasVisibleBody) {
            document.outerHtml()
        } else {
            plainTextMailDocument(extractMailPlainText(rawHtml))
        }
    }.getOrElse {
        plainTextMailDocument(extractMailPlainText(rawHtml))
    }
}

private fun parseMailDocument(rawHtml: String): Document =
    if (HTML_DOCUMENT_PATTERN.containsMatchIn(rawHtml)) {
        Jsoup.parse(rawHtml)
    } else {
        Jsoup.parseBodyFragment(rawHtml)
    }

private fun relaxInlineLayoutStyle(element: Element) {
    val cleanedDeclarations = element.attr("style")
        .split(';')
        .mapNotNull(::normalizeStyleDeclaration)
        .filterNot { declaration ->
            val property = declaration.substringBefore(':').trim().lowercase()
            property in CLIPPED_LAYOUT_PROPERTIES
        }
    val overrides = when (element.normalName()) {
        "img" -> listOf(
            "max-width: 100% !important",
            "height: auto !important",
        )

        "table" -> listOf(
            "width: 100% !important",
            "max-width: 100% !important",
            "overflow: visible !important",
        )

        "pre" -> listOf(
            "white-space: pre-wrap !important",
            "overflow-wrap: anywhere !important",
            "overflow: visible !important",
        )

        else -> listOf(
            "max-width: 100% !important",
            "overflow: visible !important",
        )
    }
    val mergedStyle = (cleanedDeclarations + overrides).joinToString("; ")
    if (mergedStyle.isBlank()) {
        element.removeAttr("style")
    } else {
        element.attr("style", mergedStyle)
    }
}

private fun normalizeStyleDeclaration(declaration: String): String? {
    val trimmed = declaration.trim()
    if (trimmed.isBlank()) return null
    val separatorIndex = trimmed.indexOf(':')
    if (separatorIndex < 0) return null
    val property = trimmed.substring(0, separatorIndex).trim()
    val value = trimmed.substring(separatorIndex + 1).trim()
    if (property.isBlank() || value.isBlank()) return null
    return "$property: $value"
}

private fun extractMailPlainText(rawHtml: String): String =
    Jsoup.parse(rawHtml).text()
        .replace(WHITESPACE_PATTERN, " ")
        .trim()
        .ifBlank { "No message content." }

private fun plainTextMailDocument(text: String): String =
    Document("").apply {
        outputSettings().prettyPrint(false)
        head()
            .appendElement("meta")
            .attr("name", "viewport")
            .attr("content", MOBILE_VIEWPORT)
        head()
            .appendElement("style")
            .attr("data-mail-mobile", "true")
            .appendText(PLAIN_TEXT_STYLE)
        body()
            .appendElement("pre")
            .addClass("mail-plain-text")
            .text(text)
    }.outerHtml()

private val HTML_DOCUMENT_PATTERN = Regex("<\\s*(html|body|head)\\b", RegexOption.IGNORE_CASE)
private val WHITESPACE_PATTERN = Regex("\\s+")

private val CLIPPED_LAYOUT_PROPERTIES = setOf(
    "width",
    "max-width",
    "height",
    "max-height",
    "overflow",
    "overflow-x",
    "overflow-y",
)

private const val MOBILE_VIEWPORT = "width=device-width, initial-scale=1, maximum-scale=1"

private const val MOBILE_MAIL_STYLE = """
html, body {
  margin: 0;
  padding: 0;
  width: 100%;
  max-width: 100%;
  background: #ffffff;
}
body.mail-mobile-body {
  padding: 16px;
  color: #111827;
  font-size: 16px;
  line-height: 1.6;
  overflow-wrap: anywhere;
  word-break: break-word;
}
body.mail-mobile-body * {
  box-sizing: border-box;
  max-width: 100% !important;
}
body.mail-mobile-body table {
  width: 100% !important;
  max-width: 100% !important;
  table-layout: fixed;
  border-collapse: collapse;
  display: block;
}
body.mail-mobile-body tbody,
body.mail-mobile-body thead,
body.mail-mobile-body tfoot,
body.mail-mobile-body tr {
  max-width: 100% !important;
}
body.mail-mobile-body td,
body.mail-mobile-body th {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}
body.mail-mobile-body img {
  max-width: 100% !important;
  height: auto !important;
}
body.mail-mobile-body pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
  overflow-x: auto;
}
body.mail-mobile-body blockquote {
  margin: 12px 0;
  padding-left: 12px;
  border-left: 4px solid #d1d5db;
}
"""

private const val PLAIN_TEXT_STYLE = """
html, body {
  margin: 0;
  padding: 0;
  width: 100%;
  max-width: 100%;
  background: #ffffff;
}
body {
  padding: 16px;
}
.mail-plain-text {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
  font-size: 16px;
  line-height: 1.6;
  color: #111827;
}
"""
