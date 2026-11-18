package com.paperlight.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.StringWriter
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class PdfEpubMode { Text, Images }

internal class PdfEpubConverter(private val context: Context) {
    suspend fun convert(
        source: File,
        destination: File,
        fallbackTitle: String,
        mode: PdfEpubMode,
        onProgress: (Int, Int) -> Unit,
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        require(source.isFile) { "The selected PDF is no longer available." }
        PDFBoxResourceLoader.init(context.applicationContext)
        val metadata = try {
            PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir))
        } catch (_: InvalidPasswordException) {
            error("Password-protected PDFs cannot be converted.")
        }
        var title = fallbackTitle
        var author = ""
        var language = "und"
        val sections = mutableListOf<Int>()

        metadata.use { document ->
            require(!document.isEncrypted) { "Encrypted PDFs cannot be converted." }
            require(document.numberOfPages > 0) { "This PDF has no pages." }
            title = document.documentInformation.title?.trim()?.takeIf { it.isNotEmpty() } ?: title
            author = document.documentInformation.author?.trim().orEmpty()
            language = document.documentCatalog.language?.trim()?.takeIf {
                it.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))
            } ?: "und"

            if (mode == PdfEpubMode.Text) {
                writeEpub(destination, title, author, language, sections, mode) { zip ->
                    val total = document.numberOfPages
                    val pageText = StringWriter()
                    var page = 0
                    val stripper = object : PDFTextStripper() {
                        override fun startPage(page: PDPage) {
                            ensureActive()
                            pageText.buffer.setLength(0)
                        }

                        override fun endPage(pdfPage: PDPage) {
                            page++
                            val paragraphs = textParagraphs(pageText.toString())
                            if (paragraphs.isNotEmpty()) {
                                sections += page
                                zip.writePage(page, "Section $page", paragraphs)
                            }
                            onProgress(page, total)
                        }
                    }.apply { setSortByPosition(true) }
                    stripper.writeText(document, pageText)
                    require(sections.isNotEmpty()) { "No selectable text found. Try the Page images mode." }
                    ensureActive()
                    onProgress(total, total)
                }
            }
        }

        if (mode == PdfEpubMode.Images) {
            val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(descriptor)
            } catch (failure: Exception) {
                descriptor.close()
                throw failure
            }
            renderer.use { pdf ->
                writeEpub(destination, title, author, language, sections, mode) { zip ->
                    for (index in 0 until pdf.pageCount) {
                        ensureActive()
                        val pageNumber = index + 1
                        pdf.openPage(index).use { page ->
                            val scale = minOf(1400f / page.width, 2100f / page.height)
                            val bitmap = Bitmap.createBitmap(
                                (page.width * scale).roundToInt().coerceAtLeast(1),
                                (page.height * scale).roundToInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                zip.putNextEntry(ZipEntry("EPUB/image-$pageNumber.jpg"))
                                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, zip)) {
                                    "Could not encode page $pageNumber."
                                }
                                zip.closeEntry()
                            } finally {
                                bitmap.recycle()
                            }
                        }
                        zip.writePage(pageNumber, "Page $pageNumber",
                            """<p><img src="image-$pageNumber.jpg" alt="PDF page $pageNumber" style="max-width:100%;height:auto;" /></p>""")
                        sections += pageNumber
                        onProgress(pageNumber, pdf.pageCount)
                    }
                }
            }
        }
        title to author
    }

    private fun writeEpub(
        destination: File,
        title: String,
        author: String,
        language: String,
        sections: List<Int>,
        mode: PdfEpubMode,
        writeContent: (ZipOutputStream) -> Unit,
    ) {
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            val mime = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(mime) }.value
            })
            zip.write(mime)
            zip.closeEntry()
            zip.writeXml("META-INF/container.xml", """
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                    <rootfiles><rootfile full-path="EPUB/book.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent())
            writeContent(zip)
            val label = if (mode == PdfEpubMode.Text) "Section" else "Page"
            val toc = sections.joinToString("") { """<li><a href="page-$it.xhtml">$label $it</a></li>""" }
            zip.writeXml("EPUB/nav.xhtml", """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$language">
                <head><title>Contents</title></head>
                <body><nav epub:type="toc"><h1>Contents</h1><ol>$toc</ol></nav></body></html>
            """.trimIndent())
            val manifest = sections.joinToString("") {
                val page = """<item id="p$it" href="page-$it.xhtml" media-type="application/xhtml+xml"/>"""
                if (mode == PdfEpubMode.Images) page + """<item id="img$it" href="image-$it.jpg" media-type="image/jpeg"/>"""
                else page
            }
            val spine = sections.joinToString("") { """<itemref idref="p$it"/>""" }
            val creator = if (author.isBlank()) "" else "<dc:creator>${xml(author)}</dc:creator>"
            val modified = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            zip.writeXml("EPUB/book.opf", """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="book-id">urn:uuid:${UUID.randomUUID()}</dc:identifier>
                    <dc:title>${xml(title)}</dc:title><dc:language>$language</dc:language>$creator
                    <meta property="dcterms:modified">$modified</meta>
                  </metadata>
                  <manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$manifest</manifest>
                  <spine>$spine</spine>
                </package>
            """.trimIndent())
        }
    }

    private fun ZipOutputStream.writePage(number: Int, label: String, body: String) {
        writeXml("EPUB/page-$number.xhtml", """
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head><title>$label</title></head><body>$body</body></html>
        """.trimIndent())
    }

    private fun ZipOutputStream.writeXml(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write("""<?xml version="1.0" encoding="UTF-8"?>""".toByteArray())
        write(content.toByteArray())
        closeEntry()
    }

    private fun textParagraphs(text: String): String = text.lineSequence()
        .map(String::trim)
        .fold(mutableListOf(StringBuilder())) { paragraphs, line ->
            val paragraph = paragraphs.last()
            if (line.isEmpty()) {
                if (paragraph.isNotEmpty()) paragraphs += StringBuilder()
            } else {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line)
            }
            paragraphs
        }.filter { it.isNotEmpty() }.joinToString("") { "<p>${xml(it.toString())}</p>" }

    private fun xml(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> if (char >= ' ' && char != '\uFFFE' && char != '\uFFFF') append(char)
            }
        }
    }
}
