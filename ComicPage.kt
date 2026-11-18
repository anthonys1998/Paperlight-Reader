package com.paperlight.reader.data

import android.graphics.BitmapFactory
import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.roundToInt

private const val MaxComicPageBytes = 24 * 1024 * 1024
private val NaturalParts = Regex("[0-9]+|[^0-9]+")

private fun imageName(name: String): Boolean =
    name.replace('\\', '/').split('/').none { it.startsWith(".") || it == "__MACOSX" } &&
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in
            setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")

private fun comparePages(first: String, second: String): Int {
    val a = NaturalParts.findAll(first.lowercase(Locale.ROOT)).iterator()
    val b = NaturalParts.findAll(second.lowercase(Locale.ROOT)).iterator()
    while (a.hasNext() && b.hasNext()) {
        val x = a.next().value
        val y = b.next().value
        val order = if (x[0] in '0'..'9' && y[0] in '0'..'9') {
            val n = x.trimStart('0')
            val m = y.trimStart('0')
            n.length.compareTo(m.length).takeIf { it != 0 } ?: n.compareTo(m)
        } else x.compareTo(y)
        if (order != 0) return order
    }
    return a.hasNext().compareTo(b.hasNext()).takeIf { it != 0 } ?: first.compareTo(second)
}

private fun <T> selectPage(entries: Sequence<T>, position: Float, name: (T) -> String): Triple<T, Int, Int> {
    val pages = entries.filter { imageName(name(it)) }.take(10_001).toList()
    require(pages.size in 1..10_000) { "The comic must contain 1 to 10,000 image pages." }
    val sorted = pages.sortedWith { a, b -> comparePages(name(a), name(b)) }
    val index = ((sorted.size - 1) * position.coerceIn(0f, 1f)).roundToInt()
    return Triple(sorted[index], index, sorted.size)
}

internal fun renderComicPage(book: BookEntity, position: Float, pixelWidth: Int): RenderedPage {
    val bytes = ByteArrayOutputStream()
    val limited = object : OutputStream() {
        override fun write(value: Int) {
            require(bytes.size() < MaxComicPageBytes) { "This comic image is too large." }
            bytes.write(value)
        }
        override fun write(source: ByteArray, offset: Int, length: Int) {
            require(length <= MaxComicPageBytes - bytes.size()) { "This comic image is too large." }
            bytes.write(source, offset, length)
        }
    }
    val (index, count) = if (book.format == "CBZ") {
        ZipFile(File(book.localPath)).use { zip ->
            val (entry, i, total) = selectPage(zip.entries().asSequence().filterNot { it.isDirectory }, position) { it.name }
            zip.getInputStream(entry).use { it.copyTo(limited) }
            i to total
        }
    } else {
        val options = ArchiveOptions.builder().maxDictionarySize(64L * 1024 * 1024).build()
        Archive(File(book.localPath), options).use { rar ->
            val (entry, i, total) = selectPage(rar.fileHeaders.asSequence().filterNot { it.isDirectory }, position) { it.fileName }
            rar.extractFile(entry, limited)
            i to total
        }
    }
    val data = bytes.toByteArray()
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, options)
    require(options.outWidth > 0 && options.outHeight > 0) { "This comic page uses an unsupported image format." }
    var sample = 1
    while (maxOf(options.outWidth, options.outHeight) / sample > pixelWidth) sample *= 2
    options.inJustDecodeBounds = false
    options.inSampleSize = sample
    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options)
        ?: error("Couldn’t decode this comic page.")
    return RenderedPage(bitmap, index, count)
}
