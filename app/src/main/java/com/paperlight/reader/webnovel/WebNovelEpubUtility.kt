package com.paperlight.reader.webnovel

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
    val requestDelayMillis: Long = 500L
)

data class WebNovelEpubResult(val file: File, val title: String, val chapterCount: Int)

/** Scrapes a user-supplied novel TOC and writes a DRM-free EPUB without blocking the UI dispatcher. */
class WebNovelEpubUtility {
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
            val toc = fetch(start.toString())
            val resolvedTocUri = validateHttpUrl(toc.baseUri())
            val links = extractChapterLinks(toc, resolvedTocUri, options.chapterLinkSelector, options.maxChapters)
            require(links.isNotEmpty()) {
                "No chapters found. This site may need a custom chapter-link selector or may block automated access."
            }

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
                val chapterDoc = fetch(url)
                val content = selectContent(chapterDoc, options.chapterContentSelector)
                content.select(options.removeSelector).remove()
                val chapterTitle = chapterDoc.selectFirst(options.chapterTitleSelector)?.text()?.cleanText()
                    ?.takeIf(String::isNotBlank)
                    ?: linkLabel
                    ?: "Chapter ${index + 1}"
                val paragraphs = extractParagraphs(content).let { paragraphs ->
                    if (paragraphs.firstOrNull() == chapterTitle) paragraphs.drop(1) else paragraphs
                }
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

    private fun fetch(url: String): Document = Jsoup.connect(url)
        .userAgent(USER_AGENT)
        .timeout(20_000)
        .maxBodySize(MAX_PAGE_BYTES)
        .followRedirects(true)
        .get()

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

    private fun selectContent(document: Document, selectors: String): Element {
        selectors.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { selector ->
            document.selectFirst(selector)?.let { return it.clone() }
        }
        return document.body().clone()
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
            "Use an http or https table-of-contents URL."
        }
        require(!uri.host.isNullOrBlank()) { "Enter a valid website URL." }
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
