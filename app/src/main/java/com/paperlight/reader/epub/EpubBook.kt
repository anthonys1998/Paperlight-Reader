package com.paperlight.reader.epub

import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

data class EpubChapter(val title: String, val file: File)
data class EpubTocEntry(val title: String, val chapterIndex: Int)
data class EpubBook(
    val title: String,
    val author: String,
    val chapters: List<EpubChapter>,
    val tableOfContents: List<EpubTocEntry>,
    val language: String = ""
)

/** Initial DRM-free EPUB 2/3 parser; advanced/fixed-layout publications remain out of scope. */
object EpubBookParser {
    private const val MAX_ENTRIES = 10_000
    private const val MAX_UNCOMPRESSED_BYTES = 300L * 1024L * 1024L

    fun open(epubFile: File, destination: File): EpubBook {
        require(epubFile.isFile) { "The imported EPUB file is missing." }
        if (!destination.exists() && !destination.mkdirs()) error("Cannot create EPUB cache directory")

        // Re-extract into a clean cache to avoid stale resources from a previous import.
        destination.listFiles()?.forEach { it.deleteRecursively() }
        val root = destination.canonicalFile
        ZipFile(epubFile).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size <= MAX_ENTRIES) { "This EPUB contains too many archive entries." }
            require(entries.sumOf { it.size.coerceAtLeast(0L) } <= MAX_UNCOMPRESSED_BYTES) {
                "This EPUB is unusually large and cannot be safely imported."
            }
            var copiedBytes = 0L
            entries.forEach { entry ->
                val out = File(root, entry.name).canonicalFile
                require(out.path.startsWith(root.path + File.separator) || out == root) {
                    "Unsafe path in EPUB archive"
                }
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                copiedBytes += count
                                require(copiedBytes <= MAX_UNCOMPRESSED_BYTES) {
                                    "This EPUB expands beyond the safe extraction limit."
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
            }
        }

        val container = File(root, "META-INF/container.xml")
        require(container.isFile) { "This file is not a valid EPUB (container.xml missing)." }
        var opfPath: String? = null
        parseXml(container) { p ->
            if (p.eventType == XmlPullParser.START_TAG && p.name == "rootfile") {
                opfPath = p.getAttributeValue(null, "full-path")
            }
        }
        val packageFile = resolveInside(root, root, opfPath ?: error("EPUB package document not found."))
        require(packageFile.isFile) { "EPUB package document is missing." }

        data class ManifestItem(val href: String, val mediaType: String, val properties: String)
        val manifest = linkedMapOf<String, ManifestItem>()
        val spine = mutableListOf<String>()
        var ncxId: String? = null
        var navId: String? = null
        var title = epubFile.nameWithoutExtension
        var author = ""
        var language = ""
        parseXml(packageFile) { p ->
            if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                "title" -> if (p.namespace?.contains("purl.org/dc/elements") == true) {
                    val value = p.nextText().trim()
                    if (value.isNotBlank()) title = value
                }
                "language" -> if (p.namespace?.contains("purl.org/dc/elements") == true) {
                    language = p.nextText().trim()
                }
                "creator" -> if (p.namespace?.contains("purl.org/dc/elements") == true) {
                    val value = p.nextText().trim()
                    if (author.isBlank() && value.isNotBlank()) author = value
                }
                "item" -> {
                    val id = p.getAttributeValue(null, "id")
                    val href = p.getAttributeValue(null, "href")
                    if (id != null && href != null) {
                        val properties = p.getAttributeValue(null, "properties") ?: ""
                        manifest[id] = ManifestItem(href, p.getAttributeValue(null, "media-type") ?: "", properties)
                        if ("nav" in properties.split(' ')) navId = id
                    }
                }
                "spine" -> ncxId = p.getAttributeValue(null, "toc")
                "itemref" -> p.getAttributeValue(null, "idref")?.let(spine::add)
            }
        }

        val packageRoot = packageFile.parentFile ?: root
        val chapters = spine.mapNotNull { id ->
            val item = manifest[id] ?: return@mapNotNull null
            val extension = item.href.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
            if (item.mediaType !in setOf("application/xhtml+xml", "text/html") && extension !in setOf("xhtml", "html", "htm")) {
                return@mapNotNull null
            }
            val file = resolveInside(root, packageRoot, item.href)
            if (file.isFile) EpubChapter(file.nameWithoutExtension.replace('_', ' ').ifBlank { title }, file) else null
        }
        require(chapters.isNotEmpty()) { "No readable XHTML chapters were found in this EPUB." }

        val lookup = chapters.mapIndexed { index, chapter -> chapter.file.canonicalPath to index }.toMap()
        val toc = mutableListOf<EpubTocEntry>()
        navId?.let(manifest::get)?.let { item ->
            val navFile = resolveInside(root, packageRoot, item.href)
            if (navFile.isFile) parseNav(navFile, root, lookup, toc)
        }
        if (toc.isEmpty()) {
            ncxId?.let(manifest::get)?.let { item ->
                val ncxFile = resolveInside(root, packageRoot, item.href)
                if (ncxFile.isFile) parseNcx(ncxFile, root, lookup, toc)
            }
        }
        if (toc.isEmpty()) chapters.forEachIndexed { index, chapter -> toc += EpubTocEntry(chapter.title, index) }
        return EpubBook(title, author, chapters, toc.distinctBy { it.chapterIndex }, language)
    }

    private fun resolveInside(root: File, base: File, href: String): File {
        val decoded = Uri.decode(href.substringBefore('#').substringBefore('?'))
        val file = File(base, decoded).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator) || file == root) {
            "Unsafe path in EPUB package."
        }
        return file
    }

    private fun parseNav(file: File, root: File, lookup: Map<String, Int>, output: MutableList<EpubTocEntry>) {
        var inToc = false
        var navDepth = 0
        var href: String? = null
        var anchorDepth = -1
        var label = StringBuilder()
        parseXml(file) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    if (p.name == "nav") {
                        navDepth++
                        val type = p.getAttributeValue("http://www.idpf.org/2007/ops", "type") ?: ""
                        if ("toc" in type.split(' ')) inToc = true
                    }
                    if (inToc && p.name == "a") {
                        href = p.getAttributeValue(null, "href")
                        anchorDepth = p.depth
                        label = StringBuilder()
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inToc && href != null) label.append(p.text)
                XmlPullParser.END_TAG -> {
                    if (inToc && p.name == "a" && href != null && p.depth == anchorDepth) {
                        val target = runCatching { resolveInside(root, file.parentFile ?: root, href!!) }.getOrNull()
                        val index = target?.canonicalPath?.let(lookup::get)
                        val text = label.toString().trim()
                        if (index != null && text.isNotBlank()) output += EpubTocEntry(text, index)
                        href = null
                    }
                    if (p.name == "nav" && navDepth > 0) {
                        navDepth--
                        if (navDepth == 0) inToc = false
                    }
                }
            }
        }
    }

    private fun parseNcx(file: File, root: File, lookup: Map<String, Int>, output: MutableList<EpubTocEntry>) {
        data class NavPoint(var label: StringBuilder = StringBuilder(), var src: String? = null)
        val stack = java.util.ArrayDeque<NavPoint>()
        var inLabel = false
        var inText = false
        parseXml(file) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "navPoint" -> stack.addLast(NavPoint())
                    "navLabel" -> inLabel = true
                    "text" -> if (inLabel) inText = true
                    "content" -> stack.peekLast()?.src = p.getAttributeValue(null, "src")
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inText) stack.peekLast()?.label?.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "text" -> inText = false
                    "navLabel" -> inLabel = false
                    "navPoint" -> {
                        val point = if (stack.isEmpty()) null else stack.removeLast()
                        val target = point?.src?.let { runCatching { resolveInside(root, file.parentFile ?: root, it) }.getOrNull() }
                        val index = target?.canonicalPath?.let(lookup::get)
                        val label = point?.label?.toString()?.trim().orEmpty()
                        if (index != null && label.isNotBlank()) output += EpubTocEntry(label, index)
                    }
                }
            }
        }
    }

    private inline fun parseXml(file: File, crossinline onEvent: (XmlPullParser) -> Unit) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        FileInputStream(file).use { input ->
            parser.setInput(input, null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                onEvent(parser)
                event = parser.next()
            }
        }
    }
}
