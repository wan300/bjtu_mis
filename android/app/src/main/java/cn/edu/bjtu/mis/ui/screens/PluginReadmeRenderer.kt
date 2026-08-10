package cn.edu.bjtu.mis.ui.screens

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import java.net.URI
import java.util.Locale

internal const val PluginReadmeMaxImages = 12

private val readmeExtensions = listOf(
    TablesExtension.create(),
    StrikethroughExtension.create(),
    TaskListItemsExtension.create(),
)

private val readmeParser: Parser = Parser.builder().extensions(readmeExtensions).build()
private val readmeHtmlRenderer: HtmlRenderer = HtmlRenderer.builder()
    .extensions(readmeExtensions)
    .escapeHtml(false)
    .build()

private val readmeSafelist: Safelist = Safelist.none()
    .addTags(
        "a", "blockquote", "br", "code", "del", "em", "h1", "h2", "h3", "h4", "h5", "h6",
        "hr", "img", "input", "li", "ol", "p", "pre", "s", "strong", "table", "tbody", "td",
        "th", "thead", "tr", "ul",
    )
    .addAttributes("a", "href", "title")
    .addAttributes("img", "alt", "height", "src", "title", "width")
    .addAttributes("input", "checked", "disabled", "type")
    .addProtocols("a", "href", "https")
    .addProtocols("img", "src", "https")

internal fun renderPluginReadmeHtml(
    markdown: String,
    owner: String,
    repository: String,
    commitSha: String,
): String {
    val source = PluginReadmeSource(owner, repository, commitSha)
    val rendered = readmeHtmlRenderer.render(readmeParser.parse(markdown))
    val document = Jsoup.parseBodyFragment(rendered)

    document.select("script, iframe, frame, frameset, object, embed, form, meta, link, style, base").remove()
    document.select("*").forEach { element ->
        element.attributes().asList()
            .filter { attribute ->
                attribute.key.startsWith("on", ignoreCase = true) ||
                    attribute.key.equals("style", ignoreCase = true) ||
                    attribute.key.equals("srcset", ignoreCase = true)
            }
            .forEach { attribute -> element.removeAttr(attribute.key) }
    }
    document.select("a[href]").forEach { anchor ->
        val target = resolveReadmeUrl(anchor.attr("href"), source.repositoryPageBase)
        if (target == null) {
            anchor.removeAttr("href")
        } else {
            anchor.attr("href", target)
        }
    }
    document.select("img[src]").toList().forEachIndexed { index, image ->
        if (index >= PluginReadmeMaxImages) {
            image.remove()
            return@forEachIndexed
        }
        val target = resolveReadmeUrl(image.attr("src"), source.imageBase)
        if (target == null || !isAllowedPluginReadmeImageUrl(target)) {
            image.remove()
        } else {
            image.attr("src", target)
        }
    }

    val cleaned = Cleaner(readmeSafelist).clean(document)
    cleaned.select("input").toList().forEach { input ->
        if (!input.attr("type").equals("checkbox", ignoreCase = true)) {
            input.remove()
        } else {
            input.attr("disabled", "")
        }
    }
    return """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https://github.com https://githubusercontent.com https://*.githubusercontent.com https://githubassets.com https://*.githubassets.com; style-src 'unsafe-inline'; base-uri 'none'; connect-src 'none'; frame-src 'none'; media-src 'none'; object-src 'none'; form-action 'none'">
          <style>
            body { box-sizing: border-box; margin: 0; padding: 16px; color: #1f2937; background: #ffffff; font: 16px/1.6 sans-serif; overflow-wrap: anywhere; }
            h1, h2, h3, h4, h5, h6 { line-height: 1.25; margin: 1.2em 0 .55em; }
            h1 { font-size: 1.7em; border-bottom: 1px solid #d1d5db; padding-bottom: .35em; }
            h2 { font-size: 1.35em; border-bottom: 1px solid #e5e7eb; padding-bottom: .25em; }
            pre { overflow-x: auto; padding: 12px; border-radius: 6px; background: #f3f4f6; }
            code { font-family: monospace; background: #f3f4f6; padding: .1em .25em; border-radius: 3px; }
            pre code { padding: 0; background: transparent; }
            blockquote { margin: 1em 0; padding-left: 12px; border-left: 3px solid #9ca3af; color: #4b5563; }
            table { display: block; max-width: 100%; overflow-x: auto; border-collapse: collapse; }
            th, td { border: 1px solid #d1d5db; padding: 6px 9px; text-align: left; }
            th { background: #f3f4f6; }
            img { display: block; max-width: 100%; height: auto; margin: .75em 0; }
            a { color: #1d4ed8; }
            input[type=checkbox] { margin-right: .45em; }
          </style>
        </head>
        <body>${cleaned.body().html()}</body>
        </html>
    """.trimIndent()
}

internal fun isAllowedPluginReadmeImageUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (uri.scheme?.lowercase(Locale.US) != "https" || uri.rawUserInfo != null) return false
    val host = uri.host?.lowercase(Locale.US) ?: return false
    return host == "github.com" ||
        host == "githubusercontent.com" ||
        host.endsWith(".githubusercontent.com") ||
        host == "githubassets.com" ||
        host.endsWith(".githubassets.com")
}

private fun resolveReadmeUrl(rawValue: String, base: URI): String? {
    val value = rawValue.trim()
    if (value.isBlank()) return null
    if (value.startsWith('#')) return value
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    val resolved = if (uri.isAbsolute) uri else base.resolve(uri)
    return resolved.takeIf {
        it.scheme?.lowercase(Locale.US) == "https" &&
            !it.host.isNullOrBlank() &&
            it.rawUserInfo == null
    }?.toASCIIString()
}

private data class PluginReadmeSource(
    val owner: String,
    val repository: String,
    val commitSha: String,
) {
    val repositoryPageBase: URI = URI("https://github.com/$owner/$repository/blob/$commitSha/")
    val imageBase: URI = URI("https://raw.githubusercontent.com/$owner/$repository/$commitSha/")
}
