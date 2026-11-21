package com.paperlight.reader.webnovel

import android.content.Context
import io.documentnode.epub4j.domain.Book
import io.documentnode.epub4j.domain.MediaType
import io.documentnode.epub4j.domain.Resource
import io.documentnode.epub4j.epub.EpubWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.io.IOException
import kotlin.random.Random
import kotlin.coroutines.coroutineContext

data class WebNovelScrapeOptions(
    val chapterLinkSelector: String = "a[href]",
    val chapterContentSelector: String = ".chapter-content, .chapter-body, #chapter-content, .read-content, .entry-content, article, main, #content",
    val chapterTitleSelector: String = "h1, h2",
    val removeSelector: String = "script, style, noscript, nav, header, footer, aside, .ads, .advertisement, .share, .comments",
    val firstChapter: Int = 1,
    val lastChapter: Int? = null,
    val requestDelayMillis: Long = 500L,
    val renderInBrowser: Boolean = false
) : java.io.Serializable

data class WebNovelEpubResult(val file: File, val title: String, val chapterCount: Int)
data class WebNovelPreview(val chapterCount: Int, val chapterTitle: String, val excerpt: String)
data class WebNovelAiSamples(val tocHtml: String, val chapterHtml: String)

/** Scrapes a user-supplied novel TOC and writes a DRM-free EPUB without blocking the UI dispatcher. */
class WebNovelEpubUtility(context: Context) {
    private val context = context.applicationContext
    private val renderer = RenderedNovelPage(this.context)
    private val cooldownPreferences = this.context.getSharedPreferences("webNovelCooldowns", Context.MODE_PRIVATE)

    suspend fun inspectForAi(
        tableOfContentsUrl: String,
        sampleChapterUrl: String = "",
        options: WebNovelScrapeOptions = WebNovelScrapeOptions(),
    ): WebNovelAiSamples = withContext(Dispatchers.IO) {
        val discoveryOptions = WebNovelScrapeOptions(renderInBrowser = options.renderInBrowser)
        val (toc, links) = chapterIndex(tableOfContentsUrl, discoveryOptions)
        val chapter = chapterDocument(sampleUrl(toc, links, sampleChapterUrl), discoveryOptions)
        WebNovelAiSamples(
            tocHtml = sanitizedSample(toc, 9_000),
            chapterHtml = sanitizedSample(chapter, 9_000),
        )
    }

    suspend fun preview(
        tableOfContentsUrl: String,
        sampleChapterUrl: String,
        options: WebNovelScrapeOptions,
    ): WebNovelPreview = withContext(Dispatchers.IO) {
        val (toc, links) = chapterIndex(tableOfContentsUrl, options)
        val url = sampleUrl(toc, links.inRange(options), sampleChapterUrl)
        val chapter = chapterDocument(url, options)
        val title = chapterTitle(chapter, options) ?: links.firstOrNull { it.first == url }?.second ?: chapter.title()
        val paragraphs = chapterParagraphs(chapter, options, title)
        require(paragraphs.isNotEmpty()) { "No readable text found in $title ($url)." }
        WebNovelPreview(links.size, title, paragraphs.joinToString("\n\n").take(550))
    }

    suspend fun createEpub(
        tableOfContentsUrl: String,
        destination: File,
        options: WebNovelScrapeOptions = WebNovelScrapeOptions(),
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): WebNovelEpubResult = withContext(Dispatchers.IO) {
        val start = validateHttpUrl(tableOfContentsUrl)
        require(options.requestDelayMillis in 0L..10_000L) { "Request delay must be between 0 and 10 seconds." }

        var completed = false
        try {
            onProgress(0, 0, "Finding chapters…")
            val (toc, allLinks) = chapterIndex(start.toString(), options)
            val links = allLinks.inRange(options)
            val bookTitle = toc.selectFirst("h1")?.text()?.trim()?.takeIf(String::isNotBlank)
                ?: toc.selectFirst("meta[property=og:site_name]")?.attr("content")?.trim()
                    ?.takeIf(String::isNotBlank)
                ?: toc.title().trim().ifBlank { start.host }

            val title = if (links.size == allLinks.size) bookTitle
                else "$bookTitle (Chapters ${options.firstChapter}–${options.lastChapter ?: allLinks.size})"
            val epub = Book().apply { metadata.addTitle(title) }
            findCoverImage(toc, start)?.let(epub::setCoverImage)
            onProgress(0, links.size, title)
            var totalTextBytes = 0L
            val checkpointDirectory = File(context.filesDir, "webnovel-checkpoints/${checkpointKey(start.toString(), options)}")
                .apply { check(isDirectory || mkdirs()) { "Could not create conversion checkpoint storage." } }
            links.forEachIndexed { index, (url, linkLabel) ->
                coroutineContext.ensureActive()
                val cacheFile = File(checkpointDirectory, "chapter-${index.toString().padStart(5, '0')}.xhtml")
                val urlFile = File(checkpointDirectory, "chapter-${index.toString().padStart(5, '0')}.url")
                val cached = cacheFile.isFile && urlFile.isFile &&
                    runCatching { urlFile.readText() == url }.getOrDefault(false)
                val xhtml = if (cached) cacheFile.readText(Charsets.UTF_8) else {
                    if (index > 0 && options.requestDelayMillis > 0L) delay(options.requestDelayMillis)
                    val chapterDoc = chapterDocument(url, options)
                    val chapterTitle = chapterTitle(chapterDoc, options)
                        ?: linkLabel
                        ?: "Chapter ${options.firstChapter + index}"
                    val paragraphs = chapterParagraphs(chapterDoc, options, chapterTitle)
                    require(paragraphs.isNotEmpty()) { "No readable text found in $chapterTitle ($url)." }
                    chapterXhtml(chapterTitle, paragraphs).also { html ->
                        atomicWrite(cacheFile, html.toByteArray(Charsets.UTF_8))
                        atomicWrite(urlFile, url.toByteArray(Charsets.UTF_8))
                    }
                }
                require(xhtml.isNotBlank()) { "A saved chapter checkpoint is empty." }
                val chapterTitle = Jsoup.parse(xhtml).selectFirst("h1")?.text()?.takeIf(String::isNotBlank)
                    ?: linkLabel ?: "Chapter ${options.firstChapter + index}"
                val bytes = xhtml.toByteArray(Charsets.UTF_8)
                totalTextBytes += bytes.size
                require(totalTextBytes <= 64L * 1024 * 1024) { "This novel is too large to generate on this device in one EPUB." }
                val resource = Resource(ByteArrayInputStream(bytes), "chapter-${(index + 1).toString().padStart(4, '0')}.xhtml")
                epub.addSection(chapterTitle, resource)
                onProgress(index + 1, links.size, if (cached) "Restored $chapterTitle" else chapterTitle)
            }

            coroutineContext.ensureActive()
            onProgress(links.size, links.size, "Writing EPUB…")
            destination.parentFile?.mkdirs()
            destination.outputStream().buffered().use { output -> EpubWriter().write(epub, output) }
            require(destination.isFile && destination.length() > 0L) { "The EPUB writer produced an empty file." }
            completed = true
            WebNovelEpubResult(destination, title, links.size)
        } finally {
            if (!completed) destination.delete()
        }
    }

    private suspend fun chapterIndex(
        url: String,
        options: WebNovelScrapeOptions,
    ): Pair<Document, List<Pair<String, String?>>> {
        val start = validateHttpUrl(url)
        fun links(document: Document): List<Pair<String, String?>> {
            val origin = validateHttpUrl(document.baseUri())
            val selector = if (origin.host.equals("novelbin.xyz", true) &&
                options.chapterLinkSelector == "a[href]"
            ) ".chapter-list a.chapter-item[href]" else options.chapterLinkSelector
            return extractChapterLinks(document, origin, selector)
        }
        val toc = if (options.renderInBrowser) {
            renderer.load(start.toString()) { document ->
                val found = links(document).size
                val expected = if (isNovelBin(document) && options.chapterLinkSelector == "a[href]") {
                    document.selectFirst(".chapter-box-count")?.text()?.substringBefore(' ')?.toIntOrNull()
                } else null
                found > 0 && (expected == null || found >= expected)
            }
        } else fetch(start.toString())
        val chapterLinks = links(toc)
        require(chapterLinks.isNotEmpty()) {
            "No chapter links found. Check the table of contents URL and selectors, or enable browser rendering."
        }
        return toc to chapterLinks
    }

    private fun <T> List<T>.inRange(options: WebNovelScrapeOptions): List<T> {
        val first = options.firstChapter
        val last = options.lastChapter ?: size
        require(first in 1..size && last in first..size) {
            "Found $size chapters. Choose a range within 1–$size."
        }
        require(last - first + 1 <= 9_000) { "Choose at most 9,000 chapters per export." }
        return subList(first - 1, last)
    }

    private fun sampleUrl(toc: Document, links: List<Pair<String, String?>>, sample: String): String {
        if (sample.isBlank()) return links.first().first
        val chapter = validateHttpUrl(sample)
        require(sameOrigin(validateHttpUrl(toc.baseUri()), chapter)) {
            "The sample chapter must be on the same site as the table of contents."
        }
        return chapter.toString()
    }

    private suspend fun chapterDocument(url: String, options: WebNovelScrapeOptions): Document {
        if (!options.renderInBrowser) return fetch(url)
        return renderer.load(url) { document ->
            val paragraphs = chapterParagraphs(document, options, chapterTitle(document, options))
            paragraphs.sumOf { it.length } >= 40
        }
    }

    private fun chapterTitle(document: Document, options: WebNovelScrapeOptions): String? {
        val selector = if (isNovelBin(document) && options.chapterTitleSelector == "h1, h2")
            ".reader-header-chapter" else options.chapterTitleSelector
        return document.selectFirst(selector)?.text()?.cleanText()?.takeIf(String::isNotBlank)
    }

    private fun chapterParagraphs(document: Document, options: WebNovelScrapeOptions, title: String?): List<String> {
        val selector = if (isNovelBin(document) && options.chapterContentSelector == WebNovelScrapeOptions().chapterContentSelector)
            ".reader-content" else options.chapterContentSelector
        val content = selectContent(document, selector, allowBodyFallback = !options.renderInBrowser)
            ?: return emptyList()
        content.select(options.removeSelector).remove()
        if (isNovelBin(document)) content.select("h1.sr-only").remove()
        return extractParagraphs(content).let { if (it.firstOrNull() == title) it.drop(1) else it }
    }

    private fun isNovelBin(document: Document): Boolean =
        validateHttpUrl(document.baseUri()).host.equals("novelbin.xyz", true)

    private suspend fun fetch(url: String): Document {
        var attempts = 0
        val host = requireNotNull(validateHttpUrl(url).host)
        while (true) {
            coroutineContext.ensureActive()
            val cooldownUntil = cooldownPreferences.getLong(host, 0L)
            if (cooldownUntil > System.currentTimeMillis()) delay(cooldownUntil - System.currentTimeMillis())
            val response = try {
                Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(20_000)
                    .maxBodySize(MAX_PAGE_BYTES)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .execute()
            } catch (failure: IOException) {
                attempts++
                if (attempts > MAX_RATE_LIMIT_RETRIES) throw failure
                persistCooldown(host, retryAfterMillis(null, attempts))
                continue
            }
            if (response.statusCode() == 429) {
                attempts++
                require(attempts <= MAX_RATE_LIMIT_RETRIES) { "The website is still rate limiting requests. Resume this conversion later." }
                persistCooldown(host, retryAfterMillis(response.header("Retry-After"), attempts))
                continue
            }
            if (response.statusCode() in 500..599) {
                attempts++
                if (attempts > MAX_RATE_LIMIT_RETRIES) error("Website returned HTTP ${response.statusCode()} after repeated retries.")
                persistCooldown(host, retryAfterMillis(null, attempts))
                continue
            }
            require(response.statusCode() in 200..299) { "Website returned HTTP ${response.statusCode()} for $url." }
            cooldownPreferences.edit().remove(host).apply()
            return response.parse()
        }
    }

    private suspend fun persistCooldown(host: String, delayMillis: Long) {
        val until = System.currentTimeMillis() + delayMillis
        cooldownPreferences.edit().putLong(host, until).apply()
        delay((until - System.currentTimeMillis()).coerceAtLeast(1L))
    }

    private fun retryAfterMillis(header: String?, attempt: Int): Long {
        val seconds = header?.trim()?.toLongOrNull()
        val serverDelay = seconds?.let { it.coerceIn(1L, MAX_RATE_LIMIT_DELAY_MILLIS / 1_000L) * 1_000L } ?: runCatching {
            val target = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
            Duration.between(java.time.Instant.now(), target.toInstant()).toMillis().coerceAtLeast(1_000L)
        }.getOrNull()
        val backoff = (1_000L shl (attempt - 1).coerceIn(0, 10)).coerceAtMost(MAX_TRANSIENT_RETRY_DELAY_MILLIS)
        val jitter = if (serverDelay == null) Random.nextLong(0L, (backoff / 4L).coerceAtLeast(1L)) else 0L
        return (serverDelay ?: (backoff + jitter)).coerceIn(1_000L, MAX_RATE_LIMIT_DELAY_MILLIS)
    }

    private fun atomicWrite(destination: File, bytes: ByteArray) {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.outputStream().buffered().use { it.write(bytes) }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            error("Could not save a completed chapter checkpoint.")
        }
    }

    private fun checkpointKey(url: String, options: WebNovelScrapeOptions): String {
        val config = listOf(url, options.chapterLinkSelector, options.chapterContentSelector,
            options.chapterTitleSelector, options.removeSelector, options.firstChapter.toString(),
            options.lastChapter?.toString().orEmpty(), options.renderInBrowser.toString()).joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(config.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun findCoverImage(toc: Document, pageUrl: URI): Resource? {
        val imageUrl = sequenceOf(
            toc.selectFirst("meta[property=og:image]")?.attr("content"),
            toc.selectFirst("meta[name=twitter:image]")?.attr("content"),
            toc.selectFirst("meta[property=twitter:image]")?.attr("content"),
            toc.selectFirst("img[src]")?.absUrl("src"),
        ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .mapNotNull { raw -> runCatching { pageUrl.resolve(raw) }.getOrNull() }
            .firstOrNull { uri ->
                (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                    !uri.host.isNullOrBlank() && uri.userInfo == null
            } ?: return null

        return runCatching {
            val response = Jsoup.connect(imageUrl.toString())
                .userAgent(USER_AGENT)
                .timeout(15_000)
                .maxBodySize(MAX_COVER_BYTES)
                .ignoreContentType(true)
                .execute()
            val mimeType = response.contentType().orEmpty().substringBefore(';').trim().lowercase()
            val extension = when (mimeType) {
                "image/jpeg", "image/jpg" -> "jpg"
                "image/png" -> "png"
                "image/gif" -> "gif"
                else -> return@runCatching null
            }
            Resource(response.bodyAsBytes(), "cover.$extension").apply {
                mediaType = MediaType(mimeType, extension)
            }
        }.getOrNull()
    }

    private fun sanitizedSample(document: Document, limit: Int): String {
        val sample = document.clone()
        sample.select("script, style, noscript, svg, iframe, object, embed, form, nav, footer, header, aside, img, video, audio").remove()
        sample.select("*").forEach { element ->
            element.attributes().asList().map { it.key }.filterNot { it in setOf("class", "id", "href") }.forEach(element::removeAttr)
            if (element.hasAttr("href")) {
                val path = runCatching { URI(element.attr("href")).path }.getOrNull().orEmpty()
                element.attr("href", path.take(240))
            }
        }
        return sample.body().html().take(limit)
    }

    private fun extractChapterLinks(
        toc: Document,
        tocUri: URI,
        selector: String
    ): List<Pair<String, String?>> {
        val candidates = toc.select(selector).mapNotNull { element ->
            val anchor = if (element.normalName() == "a") element else element.selectFirst("a[href]") ?: return@mapNotNull null
            val href = anchor.absUrl("href").ifBlank { return@mapNotNull null }
            val uri = runCatching { validateHttpUrl(href) }.getOrNull() ?: return@mapNotNull null
            if (!sameOrigin(tocUri, uri) || (uri.path == tocUri.path && uri.query == tocUri.query)) return@mapNotNull null
            val label = anchor.text().cleanText().take(160).takeIf(String::isNotBlank)
            if (label == null) return@mapNotNull null
            uri.normalize().toString() to label
        }.distinctBy { it.first }

        val chapterHint = Regex("chapter|chap\\.?|episode|ep\\.?|part|volume|第.{0,8}[章話回卷]", RegexOption.IGNORE_CASE)
        val hinted = candidates.filter { (url, label) -> chapterHint.containsMatchIn(label.orEmpty()) || chapterHint.containsMatchIn(url) }
        return hinted.ifEmpty { candidates }
    }

    private fun selectContent(document: Document, selectors: String, allowBodyFallback: Boolean): Element? {
        selectors.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { selector ->
            document.selectFirst(selector)?.let { return it.clone() }
        }
        return if (allowBodyFallback) document.body().clone() else null
    }

    private fun extractParagraphs(content: Element): List<String> {
        val blocks = content.select("p, h1, h2, h3, h4, blockquote, li")
            .map { it.text().cleanText() }
            .filter { it.length >= 2 }
        val raw = if (blocks.isNotEmpty()) blocks else content.wholeText().split(Regex("\\R+"))
            .map { it.cleanText() }.filter { it.length >= 2 }
        return raw.fold(mutableListOf<String>()) { result, paragraph ->
            if (result.lastOrNull() != paragraph) result += paragraph
            result
        }
    }

    private fun chapterXhtml(title: String, paragraphs: List<String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>")
        append(title.xmlEscape())
        append("</title><meta charset=\"UTF-8\"/><style>body{margin:5%;line-height:1.7;font-family:serif}h1{text-align:center;margin:1.5em 0}p{margin:0 0 1em;text-indent:1em}</style></head><body><h1>")
        append(title.xmlEscape())
        append("</h1>")
        paragraphs.forEach { append("<p>").append(it.xmlEscape()).append("</p>") }
        append("</body></html>")
    }

    private fun validateHttpUrl(value: String): URI {
        val uri = URI(value.trim()).normalize()
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) {
            "Use an http or https URL."
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null) { "Enter a valid website URL without embedded credentials." }
        return uri
    }

    private fun sameOrigin(base: URI, candidate: URI): Boolean =
        base.host.equals(candidate.host, ignoreCase = true) && effectivePort(base) == effectivePort(candidate)

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", true) -> 443
        else -> 80
    }

    private fun String.cleanText(): String = replace('\u00a0', ' ')
        .replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
        .replace(Regex(" *\\n *"), " ")
        .trim()

    private fun String.xmlEscape(): String = replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private companion object {
        const val MAX_RATE_LIMIT_RETRIES = 5
        const val MAX_TRANSIENT_RETRY_DELAY_MILLIS = 30L * 60 * 1_000
        const val MAX_RATE_LIMIT_DELAY_MILLIS = 7L * 24 * 60 * 60 * 1_000
        const val MAX_PAGE_BYTES = 8 * 1024 * 1024
        const val MAX_COVER_BYTES = 8 * 1024 * 1024
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36 PaperlightReader/0.10"
    }
}
