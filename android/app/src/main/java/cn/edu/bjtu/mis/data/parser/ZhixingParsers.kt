package cn.edu.bjtu.mis.data.parser

import cn.edu.bjtu.mis.data.provider.ProviderConstants
import cn.edu.bjtu.mis.model.ZhixingAttachment
import cn.edu.bjtu.mis.model.ZhixingAuthState
import cn.edu.bjtu.mis.model.ZhixingContentBlock
import cn.edu.bjtu.mis.model.ZhixingContentBlockType
import cn.edu.bjtu.mis.model.ZhixingForumEntry
import cn.edu.bjtu.mis.model.ZhixingHomeData
import cn.edu.bjtu.mis.model.ZhixingPostSummary
import cn.edu.bjtu.mis.model.ZhixingRankItem
import cn.edu.bjtu.mis.model.ZhixingSearchData
import cn.edu.bjtu.mis.model.ZhixingSearchResult
import cn.edu.bjtu.mis.model.ZhixingThreadDetail
import cn.edu.bjtu.mis.model.ZhixingThreadPost
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI

data class ParsedZhixingLoginForm(
    val actionUrl: String,
    val fields: Map<String, String>,
)

data class ParsedZhixingCaptchaForm(
    val actionUrl: String,
    val fields: Map<String, String>,
    val auth: String,
    val formhash: String,
    val loginhash: String,
    val referer: String,
    val seccodeHash: String,
    val seccodeModId: String,
)

data class ParsedZhixingSeccode(
    val seccodeHash: String,
    val seccodeModId: String,
    val imageUrl: String,
)

data class ZhixingLoginResult(
    val success: Boolean,
    val message: String,
    val remainingAttempts: Int? = null,
    val captchaRequired: Boolean = false,
    val redirectUrl: String? = null,
)

fun parseZhixingHome(html: String, pageUrl: String = "${ProviderConstants.ZHIXING_BASE_URL}/portal.php"): ZhixingHomeData {
    val document = parseZhixingDocument(html, pageUrl)
    return ZhixingHomeData(
        authState = parseZhixingAuthState(document),
        latestPosts = parseZhixingThreadLinks(document).take(40),
        forums = parseZhixingForumEntries(document),
    )
}

fun parseZhixingRanklist(html: String, pageUrl: String = "${ProviderConstants.ZHIXING_BASE_URL}/misc.php?mod=ranklist"): List<ZhixingRankItem> {
    val document = parseZhixingDocument(html, pageUrl)
    return parseZhixingThreadLinks(document)
        .take(40)
        .mapIndexed { index, post ->
            ZhixingRankItem(
                threadId = post.threadId,
                title = post.title,
                url = post.url,
                forumName = post.forumName,
                rankLabel = "第 ${index + 1} 位",
            )
        }
}

fun parseZhixingForumEntries(html: String, pageUrl: String = ProviderConstants.ZHIXING_BASE_URL): List<ZhixingForumEntry> =
    parseZhixingForumEntries(parseZhixingDocument(html, pageUrl))

fun parseZhixingThreadDetail(
    html: String,
    pageUrl: String,
    threadId: String = threadIdFromUrl(pageUrl) ?: "",
): ZhixingThreadDetail {
    val document = parseZhixingDocument(html, pageUrl)
    val canonicalUrl = document.selectFirst("link[rel=canonical]")?.absUrl("href")?.takeIf { it.isNotBlank() }
    val resolvedThreadId = threadId.takeIf { it.isNotBlank() }
        ?: canonicalUrl?.let(::threadIdFromUrl)
        ?: threadIdFromUrl(pageUrl)
        ?: ""
    val title = normalizeZhixingTitle(
        document.selectFirst("#thread_subject")?.text()
            ?: document.title().substringBefore(" - 北京交通大学论坛")
    ).ifBlank { "帖子详情" }
    val pageText = normalizeSpace(document.text())
    val restricted = isZhixingRestricted(pageText)
    val message = when {
        pageText.contains("尚未登录") -> "尚未登录，没有权限访问该版块。"
        pageText.contains("没有权限访问该版块") -> "没有权限访问该版块。"
        pageText.contains("请先登录") -> "请先登录后才能继续浏览。"
        restricted -> "当前帖子需要登录或更高权限。"
        else -> null
    }
    val posts = if (restricted) emptyList() else parseThreadPosts(document)
    val attachments = if (restricted) emptyList() else parseThreadAttachments(document)
    return ZhixingThreadDetail(
        threadId = resolvedThreadId,
        title = title,
        url = pageUrl,
        forumName = parseThreadForumName(document, title),
        canonicalUrl = canonicalUrl,
        page = pageFromUrl(canonicalUrl ?: pageUrl),
        totalPosts = posts.size,
        restricted = restricted,
        message = message,
        posts = posts,
        attachments = attachments,
    )
}

fun parseZhixingLoginForm(html: String, pageUrl: String = ProviderConstants.ZHIXING_BASE_URL): ParsedZhixingLoginForm {
    val document = parseZhixingDocument(html, pageUrl)
    val form = document.selectFirst("form[action*=logging][action*=login]")
        ?: document.selectFirst("form[action*=login]")
        ?: error("无法解析知行登录表单。")
    return ParsedZhixingLoginForm(
        actionUrl = form.absUrl("action").ifBlank { resolveZhixingUrl(pageUrl, form.attr("action")) },
        fields = formFields(form),
    )
}

fun parseZhixingCaptchaForm(html: String, pageUrl: String = ProviderConstants.ZHIXING_BASE_URL): ParsedZhixingCaptchaForm {
    val document = parseZhixingDocument(html, pageUrl)
    val form = document.selectFirst("form[action*=logging][action*=login][id^=loginform]")
        ?: document.selectFirst("form[action*=logging][action*=login]")
        ?: error("无法解析知行验证码登录表单。")
    val fields = formFields(form)
    val action = form.absUrl("action").ifBlank { resolveZhixingUrl(pageUrl, form.attr("action")) }
    val seccodeHash = document.selectFirst("[id^=seccode_]")?.id()?.removePrefix("seccode_")
        ?: fields["seccodehash"]
        ?: error("无法解析知行验证码标识。")
    return ParsedZhixingCaptchaForm(
        actionUrl = action,
        fields = fields,
        auth = fields["auth"].orEmpty(),
        formhash = fields["formhash"].orEmpty(),
        loginhash = loginHashFromUrl(action).orEmpty(),
        referer = fields["referer"].orEmpty(),
        seccodeHash = seccodeHash,
        seccodeModId = fields["seccodemodid"].orEmpty().ifBlank { "member::logging" },
    )
}

fun parseZhixingSeccodeUpdate(script: String, pageUrl: String = ProviderConstants.ZHIXING_BASE_URL): ParsedZhixingSeccode {
    val hash = Regex("""name=["']seccodehash["'][^>]*value=["']([^"']+)["']""").find(script)?.groupValues?.getOrNull(1)
        ?: Regex("""seccode_([A-Za-z0-9]+)""").find(script)?.groupValues?.getOrNull(1)
        ?: error("无法解析知行验证码 hash。")
    val modId = Regex("""name=["']seccodemodid["'][^>]*value=["']([^"']+)["']""").find(script)?.groupValues?.getOrNull(1)
        ?: "member::logging"
    val imagePath = Regex("""src=["']([^"']*misc\.php\?mod=seccode[^"']+)["']""").find(script)?.groupValues?.getOrNull(1)
        ?: Regex("""misc\.php\?mod=seccode&update=[^"']+?&idhash=$hash""").find(script)?.value
        ?: error("无法解析知行验证码图片。")
    return ParsedZhixingSeccode(
        seccodeHash = hash,
        seccodeModId = modId,
        imageUrl = resolveZhixingUrl(pageUrl, imagePath),
    )
}

fun parseZhixingLoginResponse(html: String): ZhixingLoginResult {
    val payload = discuzPayloadHtml(html)
    val message = normalizeSpace(Jsoup.parse(payload).text())
        .ifBlank { normalizeSpace(payload.replace(Regex("<[^>]+>"), " ")) }
    val failed = listOf("登录失败", "密码错误次数过多", "非法字符", "密码错误", "不存在").any { message.contains(it) }
    val success = listOf("欢迎您回来", "登录成功", "succeedhandle", "succeed", "succeeded").any { message.contains(it, ignoreCase = true) }
    val redirectUrl = Regex("""location\.href=['"]([^'"]+)['"]""").find(payload)
        ?.groupValues
        ?.getOrNull(1)
        ?.replace("&amp;", "&")
    val captchaRequired = message.contains("请输入验证码") || redirectUrl?.contains("auth=") == true
    val remainingAttempts = Regex("""还可以尝试\s*(\d+)\s*次""").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
    return ZhixingLoginResult(
        success = success && !failed,
        message = message.ifBlank {
            when {
                failed -> "登录失败"
                captchaRequired -> "请输入验证码后继续登录"
                else -> "登录请求已提交。"
            }
        },
        remainingAttempts = remainingAttempts,
        captchaRequired = captchaRequired,
        redirectUrl = redirectUrl,
    )
}

fun parseZhixingAuthState(html: String, pageUrl: String = ProviderConstants.ZHIXING_BASE_URL): ZhixingAuthState =
    parseZhixingAuthState(parseZhixingDocument(html, pageUrl))

fun parseZhixingFormHash(html: String, pageUrl: String = ProviderConstants.ZHIXING_BASE_URL): String? {
    val document = parseZhixingDocument(html, pageUrl)
    return document.selectFirst("input[name=formhash]")?.attr("value")?.takeIf { it.isNotBlank() }
        ?: Regex("""formhash=([a-zA-Z0-9]+)""").find(html)?.groupValues?.getOrNull(1)
}

fun parseZhixingSearchResults(
    html: String,
    pageUrl: String,
    keyword: String,
): ZhixingSearchData {
    val document = parseZhixingDocument(html, pageUrl)
    val results = linkedMapOf<String, ZhixingSearchResult>()
    document.select("a[href]").forEach { link ->
        val href = link.attr("href")
        val threadId = threadIdFromUrl(href) ?: return@forEach
        val title = normalizeZhixingTitle(link.text()).ifBlank { return@forEach }
        val parentText = normalizeSpace(link.parents().firstOrNull { it.tagName() in setOf("li", "tr", "div") }?.text())
        results.putIfAbsent(
            threadId,
            ZhixingSearchResult(
                threadId = threadId,
                title = title,
                url = link.absUrl("href").ifBlank { resolveZhixingUrl(document.location(), href) },
                forumName = forumNameFromContext(parentText, title),
                author = authorFromContext(parentText, title),
                postedAt = timeFromContext(parentText),
                excerpt = parentText.takeIf { it.isNotBlank() }?.let { if (it.length > 160) it.take(160) else it },
            )
        )
    }
    return ZhixingSearchData(keyword = keyword, results = results.values.toList())
}

fun isZhixingRestricted(text: String): Boolean =
    listOf("尚未登录", "没有权限访问该版块", "请先登录后才能继续浏览").any { text.contains(it) }

private fun parseZhixingDocument(html: String, pageUrl: String): Document =
    Jsoup.parse(discuzPayloadHtml(html), pageUrl)

private fun discuzPayloadHtml(html: String): String {
    val cdata = Regex("<!\\[CDATA\\[(.*)]]>", RegexOption.DOT_MATCHES_ALL).find(html)
    return cdata?.groupValues?.get(1) ?: html
}

private fun parseZhixingAuthState(document: Document): ZhixingAuthState {
    val html = document.html()
    val text = normalizeSpace(document.text())
    val username = document.selectFirst("#um .vwmy, .vwmy")?.text()?.let(::normalizeSpace)?.takeIf { it.isNotBlank() }
    val discuzUid = Regex("""discuz_uid\s*=\s*['"](\d+)['"]""").find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
    if (username != null || text.contains("退出") || (discuzUid != null && discuzUid > 0)) {
        return ZhixingAuthState(loggedIn = true, username = username, message = "已登录")
    }
    if (text.contains("请先登录") || text.contains("请 登录") || text.contains("尚未登录")) {
        return ZhixingAuthState(loggedIn = false, message = "未登录")
    }
    return ZhixingAuthState(loggedIn = false, username = username)
}

private fun parseZhixingThreadLinks(document: Document): List<ZhixingPostSummary> {
    val posts = linkedMapOf<String, ZhixingPostSummary>()
    document.select("a[href]").forEach { link ->
        val href = link.attr("href")
        val threadId = threadIdFromUrl(href) ?: return@forEach
        val title = normalizeZhixingTitle(link.text())
        if (title.isBlank() || title == "更多›") return@forEach
        val parentText = normalizeSpace(link.parent()?.text())
        posts.putIfAbsent(
            threadId,
            ZhixingPostSummary(
                threadId = threadId,
                title = title,
                url = link.absUrl("href").ifBlank { resolveZhixingUrl(document.location(), href) },
                forumName = forumNameFromContext(parentText, title),
                author = authorFromContext(parentText, title),
                excerpt = parentText.takeIf { it.isNotBlank() }?.let { if (it.length > 120) it.take(120) else it },
            )
        )
    }
    return posts.values.toList()
}

private fun parseZhixingForumEntries(document: Document): List<ZhixingForumEntry> {
    val ignored = setOf("首页 Portal", "版块列表 BBS", "手机版", "返回版块", "本版", "[进入版块]")
    val forums = linkedMapOf<String, ZhixingForumEntry>()
    document.select("a[href]").forEach { link ->
        val label = normalizeSpace(link.text())
        if (label.isBlank() || label in ignored || label.length > 24) return@forEach
        val href = link.attr("href")
        if (!isForumHref(href)) return@forEach
        val id = forumIdFromHref(href) ?: label
        forums.putIfAbsent(
            id,
            ZhixingForumEntry(
                id = id,
                name = label,
                url = link.absUrl("href").ifBlank { resolveZhixingUrl(document.location(), href) },
                description = normalizeSpace(link.parent()?.text()).takeIf { it != label },
            )
        )
    }
    return forums.values.toList()
}

private fun parseThreadPosts(document: Document): List<ZhixingThreadPost> {
    val postNodes = document.select("table[id^=pid]").takeIf { it.isNotEmpty() }
        ?: document.select("div[id^=post_]")
    val posts = postNodes.mapIndexedNotNull { index, post ->
        val contentElement = post.selectFirst("td[id^=postmessage_], .t_f, td.t_f, .pcb")
        val contentBlocks = parsePostContentBlocks(contentElement, document.location())
        val content = contentBlocks
            .filter { it.type == ZhixingContentBlockType.Text }
            .joinToString(" ") { it.text.orEmpty() }
            .let(::normalizeSpace)
            .ifBlank { cleanPostContent(contentElement) }
            .takeIf { it.isNotBlank() }
            ?: return@mapIndexedNotNull null
        ZhixingThreadPost(
            author = post.selectFirst(".authi a.xw1, .authi a[href*=space-uid], .pls .xw1, a.xw1")
                ?.text()
                ?.let(::normalizeSpace)
                ?.takeIf { it.isNotBlank() },
            floor = parsePostFloor(post) ?: "${index + 1}楼",
            postedAt = post.selectFirst("em[id^=authorposton]")
                ?.text()
                ?.removePrefix("发表于")
                ?.let(::normalizeSpace)
                ?.takeIf { it.isNotBlank() },
            content = content,
            contentBlocks = contentBlocks.ifEmpty {
                listOf(ZhixingContentBlock(type = ZhixingContentBlockType.Text, text = content))
            },
        )
    }
    if (posts.isNotEmpty()) return posts
    val fallback = normalizeSpace(document.selectFirst("#postlist, #threadlist, body")?.text())
    return fallback.takeIf { it.isNotBlank() }?.let {
        val content = it.take(2000)
        listOf(
            ZhixingThreadPost(
                content = content,
                contentBlocks = listOf(ZhixingContentBlock(type = ZhixingContentBlockType.Text, text = content)),
            )
        )
    }.orEmpty()
}

private fun parseThreadAttachments(document: Document): List<ZhixingAttachment> {
    val attachments = linkedMapOf<String, ZhixingAttachment>()
    document.select("a[href*=attachment.php], .attach, dl.tattl, .t_attach").forEach { element ->
        val link = if (element.`is`("a[href]")) element else element.selectFirst("a[href]")
        val text = normalizeSpace(element.text())
            .replace("下载附件", "")
            .replace("保存到相册", "")
            .trim()
        val name = normalizeSpace(link?.text()).ifBlank {
            Regex("""[^\s]+\.(?:png|jpe?g|gif|webp|pdf|docx?|xlsx?|pptx?|zip|rar|7z|txt)""", RegexOption.IGNORE_CASE)
                .find(text)
                ?.value
                .orEmpty()
        }.ifBlank { return@forEach }
        val size = Regex("""\(([^)]*(?:KB|MB|GB|B)[^)]*)\)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
        attachments.putIfAbsent(
            name,
            ZhixingAttachment(
                name = name,
                url = link?.absUrl("href")?.takeIf { it.isNotBlank() },
                size = size,
            )
        )
    }
    return attachments.values.toList()
}

private fun cleanPostContent(element: Element?): String {
    val clone = element?.clone() ?: return ""
    clone.select("script, style, .pob, .po, .cm, .quote, .locked, .t_attach, .attach, dl.tattl, .attach_tips").remove()
    return normalizeSpace(clone.text())
        .replace("下载附件", "")
        .replace("保存到相册", "")
        .replace("post_newreply", "")
        .trim()
}

private fun parsePostContentBlocks(element: Element?, pageUrl: String): List<ZhixingContentBlock> {
    val clone = element?.clone() ?: return emptyList()
    clone.select("script, style, .pob, .po, .cm, .quote, .locked, .t_attach, .attach, dl.tattl, .attach_tips").remove()
    val blocks = mutableListOf<ZhixingContentBlock>()

    fun appendText(value: String) {
        val text = normalizeSpace(value)
            .replace("下载附件", "")
            .replace("保存到相册", "")
            .replace("post_newreply", "")
            .trim()
        if (text.isBlank()) return
        val last = blocks.lastOrNull()
        if (last?.type == ZhixingContentBlockType.Text) {
            blocks[blocks.lastIndex] = last.copy(text = normalizeSpace("${last.text.orEmpty()} $text"))
        } else {
            blocks += ZhixingContentBlock(type = ZhixingContentBlockType.Text, text = text)
        }
    }

    fun walk(node: Node) {
        when (node) {
            is TextNode -> appendText(node.text())
            is Element -> {
                if (node.tagName().equals("br", ignoreCase = true)) {
                    appendText("\n")
                    return
                }
                val imageUrl = postImageUrl(node, pageUrl)
                if (imageUrl != null) {
                    blocks += ZhixingContentBlock(
                        type = ZhixingContentBlockType.Image,
                        imageUrl = imageUrl,
                        alt = normalizeSpace(node.attr("alt").ifBlank { node.attr("title") }).takeIf { it.isNotBlank() },
                    )
                    return
                }
                node.childNodes().forEach(::walk)
            }
            else -> node.childNodes().forEach(::walk)
        }
    }

    clone.childNodes().forEach(::walk)
    return blocks
}

private fun postImageUrl(element: Element, pageUrl: String): String? {
    if (!element.tagName().equals("img", ignoreCase = true)) return null
    val attrName = listOf("file", "zoomfile", "data-original", "data-src", "src")
        .firstOrNull { element.attr(it).isNotBlank() }
        ?: return null
    val raw = element.attr(attrName).replace("&amp;", "&").trim()
    if (raw.isBlank()) return null
    return element.absUrl(attrName)
        .ifBlank { resolveZhixingUrl(pageUrl, raw) }
        .takeIf { it.isNotBlank() }
}

private fun parsePostFloor(post: Element): String? {
    val candidates = listOfNotNull(
        post.selectFirst(".pi strong a")?.text(),
        post.selectFirst(".pi strong")?.text(),
        post.selectFirst(".postnum")?.text(),
        post.selectFirst("a[href*=goto]")?.text(),
    ).map(::normalizeSpace)
    return candidates.firstOrNull { value ->
        value.isNotBlank() &&
            !value.contains("发表于") &&
            (value.contains("楼") || value.startsWith("#") || value in setOf("楼主", "沙发", "板凳", "地板"))
    }
}

private fun parseThreadForumName(document: Document, title: String): String? {
    val crumbs = document.select("#pt a, #pt em, .z a, .z em")
        .map { normalizeSpace(it.text()) }
        .filter { it.isNotBlank() }
    val titleIndex = crumbs.indexOfLast { it == title }
    return when {
        titleIndex > 0 -> crumbs.getOrNull(titleIndex - 1)
        crumbs.size >= 2 -> crumbs.getOrNull(crumbs.lastIndex - 1)
        else -> null
    }?.takeIf { it !in setOf("版块列表", "北京交通大学论坛-知行信息交流平台") }
}

private fun formFields(form: Element): Map<String, String> {
    val fields = linkedMapOf<String, String>()
    form.select("input[name], textarea[name], select[name]").forEach { field ->
        val name = field.attr("name").trim()
        if (name.isBlank()) return@forEach
        val value = if (field.tagName().equals("select", ignoreCase = true)) {
            field.selectFirst("option[selected]")?.attr("value")
                ?: field.selectFirst("option")?.attr("value")
                ?: ""
        } else {
            field.attr("value")
        }
        fields[name] = value
    }
    return fields
}

private fun normalizeZhixingTitle(value: String?): String =
    normalizeSpace(value)
        .removeSuffix("- Powered by Discuz!")
        .trim()

private fun forumNameFromContext(context: String, title: String): String? {
    val bracket = Regex("\\[([^]]+)]\\s*.*${Regex.escape(title)}").find(context)
    if (bracket != null) return bracket.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
    val forum = Regex("""版块[:：]\s*([^\s]+)""").find(context)
    return forum?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
}

private fun authorFromContext(context: String, title: String): String? {
    val beforeTitle = context.substringBefore(title, "")
    return beforeTitle.split(" ")
        .lastOrNull { it.isNotBlank() && !it.startsWith("[") && !it.endsWith("]") }
        ?.takeIf { it.length <= 32 }
}

private fun timeFromContext(context: String): String? =
    Regex("""20\d{2}[-/年]\d{1,2}[-/月]\d{1,2}(?:\s+\d{1,2}:\d{2})?""")
        .find(context)
        ?.value

private fun isForumHref(href: String): Boolean =
    href.contains("forum.php?gid=") ||
        href.contains("forum.php?mod=forumdisplay") ||
        Regex("""(?:^|/)forum-\d+-\d+\.html""").containsMatchIn(href)

private fun forumIdFromHref(href: String): String? =
    Regex("""(?:[?&](?:fid|gid)=|forum-)(\d+)""").find(href)?.groupValues?.getOrNull(1)

private fun loginHashFromUrl(url: String): String? =
    Regex("""[?&]loginhash=([A-Za-z0-9]+)""").find(url)?.groupValues?.getOrNull(1)

private fun pageFromUrl(url: String): Int =
    Regex("""(?:^|/)thread-\d+-(\d+)-\d+\.html""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("""[?&]page=(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: 1

fun threadIdFromUrl(url: String): String? =
    Regex("""(?:^|/)thread-(\d+)-\d+-\d+\.html""").find(url)?.groupValues?.getOrNull(1)
        ?: Regex("""[?&]tid=(\d+)""").find(url)?.groupValues?.getOrNull(1)

fun resolveZhixingUrl(baseUrl: String, href: String): String {
    if (href.startsWith("http://", ignoreCase = true) || href.startsWith("https://", ignoreCase = true)) return href
    val base = baseUrl.takeIf { it.isNotBlank() } ?: "${ProviderConstants.ZHIXING_BASE_URL}/"
    return runCatching { URI(base).resolve(href).toString() }
        .getOrElse { "${ProviderConstants.ZHIXING_BASE_URL}/${href.trimStart('/')}" }
}
