package com.paperlight.reader.webnovel

import android.content.Context
import io.documentnode.epub4j.domain.Book
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
import kotlin.coroutines.coroutineContext

data class WebNovelScrapeOptions(
    val chapterLinkSelector: String = "a[href]",
    val chapterContentSelector: String = ".chapter-content, .chapter-body, #chapter-content, .read-content, .entry-content, article, main, #content",
    val chapterTitleSelector: String = "h1, h2",
    val removeSelector: String = "script, style, noscript, nav, header, footer, aside, .ads, .advertisement, .share, .comments",
    val maxChapters: Int = 2_000,
    val requestDelayMillis: Long = 500L,
    val renderInBrowser: Boolean = false
)

data class WebNovelEpubResult(val file: File, val title: String, val chapterCount: Int)
data class WebNovelPreview(val chapterCount: Int, val chapterTitle: String, val excerpt: String)
data class WebNovelAiSamples(val tocHtml: String, val chapterHtml: String)

/** Scrapes a user-supplied novel TOC and writes a DRM-free EPUB without blocking the UI dispatcher. */
class WebNovelEpubUtility(context: Context) {
    private val renderer = RenderedNovelPage(context)

    suspend fun inspectForAi(
        tableOfContentsUrl: String,
        sampleChapterUrl: String = "",
        options: WebNovelScrapeOptions = WebNovelScrapeOptions(),
    ): WebNovelAiSamples = withContext(Dispatchers.IO) {
        val discoveryOptions = WebNovelScrapeOptions(maxChapters = Int.MAX_VALUE, renderInBrowser = options.renderInBrowser)
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
        val url = sampleUrl(toc, links, sampleChapterUrl)
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
        require(options.maxChapters in 1..9_000) { "Chapter limit must be between 1 and 9,000." }
        require(options.requestDelayMillis in 0L..10_000L) { "Request delay must be between 0 and 10 seconds." }

        var completed = false
        try {
            onProgress(0, 0, "Finding chapters…")
            val (toc, links) = chapterIndex(start.toString(), options)
            val title = toc.selectFirst("h1")?.text()?.trim()?.takeIf(String::isNotBlank)
                ?: toc.selectFirst("meta[property=og:site_name]")?.attr("content")?.trim()
                    ?.takeIf(String::isNotBlank)
                ?: toc.title().trim().ifBlank { start.host }

            val epub = Book().apply { metadata.addTitle(title) }
            onProgress(0, links.size, title)
            var totalTextBytes = 0L
            links.forEachIndexed { index, (url, linkLabel) ->
                coroutineContext.ensureActive()
                if (index > 0 && options.requestDelayMillis > 0L) delay(options.requestDelayMillis)
                val chapterDoc = chapterDocument(url, options)
                val chapterTitle = chapterTitle(chapterDoc, options)
                    ?: linkLabel
                    ?: "Chapter ${index + 1}"
                val paragraphs = chapterParagraphs(chapterDoc, options, chapterTitle)
                require(paragraphs.isNotEmpty()) { "No readable text found in $chapterTitle ($url)." }
                val xhtml = chapterXhtml(chapterTitle, paragraphs)
                val bytes = xhtml.toByteArray(Charsets.UTF_8)
                totalTextBytes += bytes.size
                require(totalTextBytes <= 64L * 1024 * 1024) { "This novel is too large to generate on this device in one EPUB." }
                val resource = Resource(ByteArrayInputStream(bytes), "chapter-${(index + 1).toString().padStart(4, '0')}.xhtml")
                epub.addSection(chapterTitle, resource)
                onProgress(index + 1, links.size, chapterTitle)
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
        fun links(document: Document, limit: Int): List<Pair<String, String?>> {
            val origin = validateHttpUrl(document.baseUri())
            val selector = if (origin.host.equals("novelbin.xyz", true) &&
                options.chapterLinkSelector == "a[href]"
            ) ".chapter-list a.chapter-item[href]" else options.chapterLinkSelector
            return extractChapterLinks(document, origin, selector, limit)
        }
        val toc = if (options.renderInBrowser) {
            renderer.load(start.toString()) { document ->
                val found = links(document, Int.MAX_VALUE).size
                val expected = if (isNovelBin(document) && options.chapterLinkSelector == "a[href]") {
                    document.selectFirst(".chapter-box-count")?.text()?.substringBefore(' ')?.toIntOrNull()
                } else null
                found > 0 && (expected == null || found >= expected)
            }
        } else fetch(start.toString())
        val chapterLinks = links(toc, options.maxChapters)
        require(chapterLinks.isNotEmpty()) {
            "No chapter links found. Check the table of contents URL and selectors, or enable browser rendering."
        }
        return toc to chapterLinks
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

    private fun fetch(url: String): Document = Jsoup.connect(url)
        .userAgent(USER_AGENT)
        .timeout(20_000)
        .maxBodySize(MAX_PAGE_BYTES)
        .followRedirects(true)
        .get()

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
        selector: String,
        limit: Int
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
        val chapters = hinted.ifEmpty { candidates }
        require(chapters.size <= limit) { "Found ${chapters.size} chapters. Increase the chapter limit in advanced options to download them all." }
        return chapters
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
        const val MAX_PAGE_BYTES = 8 * 1024 * 1024
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36 PaperlightReader/0.10"
    }
}
