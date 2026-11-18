package com.paperlight.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

enum class PdfEpubMode { Text, Images }

internal class PdfEpubConverter(private val context: Context) {
    private data class TextLine(val text: String, val y: Float, val x: Float, val fontSize: Float)
    private data class TextPage(val number: Int, val lines: List<TextLine>)
    private data class Section(val number: Int, val title: String, val body: String)

    suspend fun convert(
        source: File,
        destination: File,
        fallbackTitle: String,
        mode: PdfEpubMode,
        onProgress: (Int, Int) -> Unit,
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        require(source.isFile) { "The selected PDF is no longer available." }
        PDFBoxResourceLoader.init(context.applicationContext)
        val document = try {
            PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir))
        } catch (_: InvalidPasswordException) {
            error("Password-protected PDFs cannot be converted.")
        }
        var title = fallbackTitle
        var author = ""
        var language = "und"
        document.use { pdf ->
            require(!pdf.isEncrypted) { "Encrypted PDFs cannot be converted." }
            require(pdf.numberOfPages > 0) { "This PDF has no pages." }
            title = pdf.documentInformation.title?.trim()?.takeIf(String::isNotEmpty) ?: title
            author = pdf.documentInformation.author?.trim().orEmpty()
            language = pdf.documentCatalog.language?.trim()?.takeIf {
                it.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))
            } ?: "und"

            if (mode == PdfEpubMode.Text) {
                val total = pdf.numberOfPages
                val pages = extractTextPages(pdf) { onProgress(it, total) }
                val ocrPages = pages.filter { it.lines.sumOf { line -> line.text.length } < 35 }.map { it.number }.toSet()
                val resolved = if (ocrPages.isEmpty()) pages else {
                    val recognized = recognizePages(source, pages.size, ocrPages) { onProgress(it, total) }
                    pages.map { page ->
                        if (page.number in ocrPages && recognized[page.number].orEmpty().isNotEmpty())
                            page.copy(lines = recognized.getValue(page.number)) else page
                    }
                }
                val filtered = removeRepeatedHeadersAndFooters(resolved)
                val sections = makeSections(filtered)
                require(sections.isNotEmpty()) { "No text could be extracted. Try the Page images mode." }
                writeEpub(destination, title, author, language, sections, mode) { zip ->
                    sections.forEachIndexed { index, section ->
                        ensureActive()
                        zip.writePage(index + 1, section.title, section.body)
                    }
                }
                onProgress(total, total)
            } else {
                val sections = mutableListOf<Section>()
                val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = try { PdfRenderer(descriptor) } catch (failure: Exception) {
                    descriptor.close()
                    throw failure
                }
                renderer.use { pdfRenderer ->
                    writeEpub(destination, title, author, language, sections, mode) { zip ->
                        for (index in 0 until pdfRenderer.pageCount) {
                            ensureActive()
                            val pageNumber = index + 1
                            pdfRenderer.openPage(index).use { page ->
                                val scale = minOf(1400f / page.width, 2100f / page.height)
                                val bitmap = Bitmap.createBitmap(
                                    (page.width * scale).roundToInt().coerceAtLeast(1),
                                    (page.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888,
                                )
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    zip.putNextEntry(ZipEntry("EPUB/image-$pageNumber.jpg"))
                                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, zip)) { "Could not encode page $pageNumber." }
                                    zip.closeEntry()
                                } finally { bitmap.recycle() }
                            }
                            sections += Section(pageNumber, "Page $pageNumber", "<p><img src=\"image-$pageNumber.jpg\" alt=\"PDF page $pageNumber\" style=\"max-width:100%;height:auto;\" /></p>")
                            zip.writePage(pageNumber, "Page $pageNumber", sections.last().body)
                            onProgress(pageNumber, pdfRenderer.pageCount)
                        }
                    }
                }
            }
        }
        title to author
    }

    private fun extractTextPages(document: PDDocument, progress: (Int) -> Unit): List<TextPage> {
        val result = mutableListOf<TextPage>()
        var current = mutableListOf<TextLine>()
        var pageNumber = 0
        val stripper = object : PDFTextStripper() {
            override fun startPage(page: PDPage) {
                current = mutableListOf()
            }
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                val clean = text.trim().replace(Regex("\\s+"), " ")
                if (clean.isEmpty()) return
                val positions = textPositions.filter { !it.unicode.isNullOrBlank() }
                current += TextLine(
                    text = clean,
                    y = positions.minOfOrNull { it.yDirAdj } ?: 0f,
                    x = positions.minOfOrNull { it.xDirAdj } ?: 0f,
                    fontSize = positions.map { it.fontSizeInPt }.average().toFloat().takeIf { it.isFinite() } ?: 0f,
                )
            }
            override fun endPage(page: PDPage) {
                pageNumber++
                result += TextPage(pageNumber, current.sortedWith(compareBy<TextLine> { it.y }.thenBy { it.x }))
                progress(pageNumber)
            }
        }.apply { setSortByPosition(true) }
        stripper.writeText(document, java.io.Writer.nullWriter())
        return result
    }

    private suspend fun recognizePages(source: File, pageCount: Int, targets: Set<Int>, progress: (Int) -> Unit): Map<Int, List<TextLine>> {
        val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try { PdfRenderer(descriptor) } catch (failure: Exception) { descriptor.close(); throw failure }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val found = mutableMapOf<Int, List<TextLine>>()
        try {
            renderer.use { pdf ->
                for (number in 1..pageCount) {
                    if (number !in targets) continue
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    pdf.openPage(number - 1).use { page ->
                        val scale = minOf(2200f / page.width, 3000f / page.height).coerceAtLeast(1f)
                        val bitmap = Bitmap.createBitmap((page.width * scale).roundToInt(), (page.height * scale).roundToInt(), Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                            found[number] = result.textBlocks.flatMap { block -> block.lines }.map { line ->
                                val box = line.boundingBox
                                TextLine(line.text.trim(), (box?.top ?: 0) / scale, (box?.left ?: 0) / scale,
                                    ((box?.height() ?: 0) / scale).toFloat().coerceAtLeast(1f))
                            }.sortedWith(compareBy<TextLine> { it.y }.thenBy { it.x })
                        } finally { bitmap.recycle() }
                    }
                    progress(number)
                }
            }
        } finally { recognizer.close() }
        return found
    }

    private fun removeRepeatedHeadersAndFooters(pages: List<TextPage>): List<TextPage> {
        if (pages.size < 3) return pages
        val threshold = (pages.size * 0.35f).roundToInt().coerceAtLeast(3)
        val occurrences = mutableMapOf<String, Int>()
        pages.forEach { page ->
            val edgeLines = page.lines.filter { it.y < 90f || it.y > 650f }
            edgeLines.map { normalizeHeader(it.text) }.filter(String::isNotBlank).distinct().forEach { key ->
                occurrences[key] = (occurrences[key] ?: 0) + 1
            }
        }
        val repeated = occurrences.filterValues { it >= threshold }.keys
        if (repeated.isEmpty()) return pages
        return pages.map { page -> page.copy(lines = page.lines.filterNot { line ->
            (line.y < 90f || line.y > 650f) && normalizeHeader(line.text) in repeated
        }) }
    }

    private fun normalizeHeader(text: String): String = text.lowercase().replace(Regex("\\d+"), "#").replace(Regex("\\s+"), " ").trim()

    private fun makeSections(pages: List<TextPage>): List<Section> {
        val sections = mutableListOf<Section>()
        pages.forEach { page ->
            val lines = page.lines
            if (lines.isEmpty()) return@forEach
            val median = lines.map { it.fontSize }.filter { it > 0 }.sorted().let { values -> values.getOrNull(values.size / 2) ?: 10f }
            val headingIndex = lines.indexOfFirst { line ->
                line.text.length <= 100 && (line.text.matches(Regex("(?i)^(chapter|part|book|prologue|epilogue|appendix)\\b.*")) ||
                    (line.fontSize >= median * 1.35f && line.text.length <= 80))
            }
            val heading = if (headingIndex >= 0) lines[headingIndex].text else "Page ${page.number}"
            val body = paragraphs(lines.filterIndexed { index, _ -> index != headingIndex })
            val headHtml = if (headingIndex >= 0) "<h1>${xml(heading)}</h1>" else ""
            val sectionBody = headHtml + body
            if (sectionBody.isNotBlank()) sections += Section(page.number, heading, sectionBody)
        }
        return sections
    }

    private fun paragraphs(lines: List<TextLine>): String {
        if (lines.isEmpty()) return ""
        val typical = lines.map { it.fontSize }.filter { it > 0 }.sorted().let { it.getOrNull(it.size / 2) ?: 10f }
        val output = StringBuilder()
        var paragraph = StringBuilder()
        var previous: TextLine? = null
        fun flush() {
            if (paragraph.isNotEmpty()) {
                output.append("<p>").append(xml(paragraph.toString().trim())).append("</p>")
                paragraph = StringBuilder()
            }
        }
        for (line in lines) {
            val prior = previous
            val gap = if (prior == null) 0f else line.y - prior.y
            val newParagraph = prior != null && gap > max(typical * 1.65f, 17f)
            if (newParagraph) flush()
            val value = line.text
            if (paragraph.isNotEmpty()) {
                if (paragraph.endsWith("-") && value.firstOrNull()?.isLowerCase() == true) paragraph.setLength(paragraph.length - 1)
                else paragraph.append(' ')
            }
            paragraph.append(value)
            previous = line
        }
        flush()
        return output.toString()
    }

    private fun writeEpub(destination: File, title: String, author: String, language: String, sections: List<Section>, mode: PdfEpubMode, writeContent: (ZipOutputStream) -> Unit) {
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            val mime = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size; crc = CRC32().apply { update(mime) }.value })
            zip.write(mime); zip.closeEntry()
            zip.writeXml("META-INF/container.xml", "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" version=\"1.0\"><rootfiles><rootfile full-path=\"EPUB/book.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>")
            writeContent(zip)
            val toc = sections.mapIndexed { index, item -> "<li><a href=\"page-${index + 1}.xhtml\">${xml(item.title)}</a></li>" }.joinToString("")
            zip.writeXml("EPUB/nav.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"$language\"><head><title>Contents</title></head><body><nav epub:type=\"toc\"><h1>Contents</h1><ol>$toc</ol></nav></body></html>")
            val manifest = sections.mapIndexed { index, _ ->
                val item = "<item id=\"p${index + 1}\" href=\"page-${index + 1}.xhtml\" media-type=\"application/xhtml+xml\"/>"
                if (mode == PdfEpubMode.Images) item + "<item id=\"img${index + 1}\" href=\"image-${index + 1}.jpg\" media-type=\"image/jpeg\"/>" else item
            }.joinToString("")
            val spine = sections.indices.joinToString("") { "<itemref idref=\"p${it + 1}\"/>" }
            val creator = if (author.isBlank()) "" else "<dc:creator>${xml(author)}</dc:creator>"
            val modified = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            zip.writeXml("EPUB/book.opf", "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"book-id\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"book-id\">urn:uuid:${UUID.randomUUID()}</dc:identifier><dc:title>${xml(title)}</dc:title><dc:language>$language</dc:language>$creator<meta property=\"dcterms:modified\">$modified</meta></metadata><manifest><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>$manifest</manifest><spine>$spine</spine></package>")
        }
    }

    private fun ZipOutputStream.writePage(number: Int, label: String, body: String) = writeXml("EPUB/page-$number.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>${xml(label)}</title></head><body>$body</body></html>")
    private fun ZipOutputStream.writeXml(name: String, content: String) {
        putNextEntry(ZipEntry(name)); write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>".toByteArray()); write(content.toByteArray()); closeEntry()
    }
    private fun xml(value: String): String = buildString {
        value.forEach { char -> when (char) { '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); else -> if (char >= ' ' && char != '\uFFFE' && char != '\uFFFF') append(char) } }
    }
}
