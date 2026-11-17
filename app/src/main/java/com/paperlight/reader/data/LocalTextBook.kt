package com.paperlight.reader.data

import com.paperlight.reader.epub.EpubBook
import com.paperlight.reader.epub.EpubChapter
import com.paperlight.reader.epub.EpubTocEntry
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

private val ParagraphGap = Regex("\\n[ \\t]*\\n+")

internal fun openLocalText(book: BookEntity, directory: File): EpubBook {
    val source = File(book.localPath)
    val limit = if (book.format == "TXT") 16L * 1024 * 1024 else 64L * 1024 * 1024
    require(source.length() in 1..limit) { "This ${book.format} file is empty or too large to open." }
    directory.deleteRecursively()
    check(directory.mkdirs()) { "Couldn’t prepare this book." }
    val chapters = try {
        if (book.format == "TXT") textChapters(source, directory)
        else KindleImport.chapters(source, directory)
    } catch (failure: Exception) {
        directory.deleteRecursively()
        throw failure
    }
    val contents = chapters.mapIndexed { index, chapter -> EpubTocEntry(chapter.title, index) }
    return EpubBook(book.title, book.author, chapters, contents)
}

private fun textChapters(source: File, directory: File): List<EpubChapter> {
    val bytes = source.readBytes()
    val text = when {
        bytes.startsWithBytes(0xff, 0xfe) -> String(bytes, Charsets.UTF_16LE)
        bytes.startsWithBytes(0xfe, 0xff) -> String(bytes, Charsets.UTF_16BE)
        else -> runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrElse { String(bytes, charset("windows-1252")) }
    }.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    require(text.isNotBlank()) { "This text file is empty." }
    val chapters = mutableListOf<EpubChapter>()
    var start = 0
    while (start < text.length) {
        val target = (start + 30_000).coerceAtMost(text.length)
        val newline = if (target == text.length) -1 else text.lastIndexOf('\n', target - 1)
        var end = if (newline > start + 15_000) newline + 1 else target
        if (end < text.length && text[end - 1].isHighSurrogate()) end--
        val title = "Section ${chapters.size + 1}"
        val file = File(directory, "text-${chapters.size}.html")
        file.bufferedWriter().use { writer ->
            writer.write("<html><head><title>$title</title></head><body>")
            for (paragraph in text.substring(start, end).split(ParagraphGap)) {
                writer.write("<p>")
                writer.write(paragraph.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>"))
                writer.write("</p>")
            }
            writer.write("</body></html>")
        }
        chapters += EpubChapter(title, file)
        start = end
    }
    return chapters
}

private fun ByteArray.startsWithBytes(first: Int, second: Int): Boolean =
    size >= 2 && (this[0].toInt() and 255) == first && (this[1].toInt() and 255) == second

internal object KindleImport {
    init { System.loadLibrary("paperlight_kindle") }

    private external fun unpack(source: String, target: String): Int

    fun chapters(source: File, directory: File): List<EpubChapter> {
        when (unpack(source.absolutePath, directory.absolutePath)) {
            0 -> Unit
            2 -> error("This Kindle book is DRM protected. Paperlight opens DRM-free copies.")
            3 -> error("This Kindle book expands beyond Paperlight’s size limit.")
            4 -> error("Couldn’t write the local Kindle reading copy.")
            else -> error("Couldn’t read this MOBI or AZW book.")
        }
        val parts = directory.listFiles().orEmpty().mapNotNull { file ->
            val number = file.name.removePrefix("part").removeSuffix(".html").toIntOrNull()
            if (file.name.startsWith("part") && file.extension == "html" && number != null) number to file else null
        }.sortedBy { it.first }
        require(parts.isNotEmpty()) { "This Kindle file has no readable reflowable sections." }
        return parts.mapIndexed { index, (_, file) -> EpubChapter("Section ${index + 1}", file) }
    }
}
