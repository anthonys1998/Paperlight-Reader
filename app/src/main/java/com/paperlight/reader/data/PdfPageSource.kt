package com.paperlight.reader.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** One renderer per open document. Serialize page access and bound bitmap memory. */
internal class PdfPageSource(private val path: String) {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private var renderer: PdfRenderer? = null
    private val cache = object : LruCache<String, RenderedPage>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: RenderedPage): Int = value.bitmap.byteCount
    }

    suspend fun render(position: Float, pixelWidth: Int): RenderedPage = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureActive()
            check(!closed.get()) { "This document has been closed." }
            val pdf = renderer ?: run {
                val descriptor = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                try { PdfRenderer(descriptor) } catch (failure: Exception) {
                    descriptor.close()
                    throw failure
                }
            }.also { renderer = it }
            require(pdf.pageCount > 0) { "This PDF has no pages." }
            val index = ((pdf.pageCount - 1) * position.coerceIn(0f, 1f)).roundToInt()
            val key = "$index:$pixelWidth"
            cache.get(key)?.let { return@withLock it }
            pdf.openPage(index).use { page ->
                val scale = minOf(pixelWidth.toFloat() / page.width, 2600f / page.height)
                val bitmap = Bitmap.createBitmap((page.width * scale).roundToInt().coerceAtLeast(1),
                    (page.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    RenderedPage(bitmap, index, pdf.pageCount).also { cache.put(key, it) }
                } catch (failure: Exception) {
                    bitmap.recycle()
                    throw failure
                }
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        // A render already in progress may finish on IO. Never wait for it on UI.
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                renderer?.close()
                renderer = null
                cache.evictAll()
            }
        }
    }
}
