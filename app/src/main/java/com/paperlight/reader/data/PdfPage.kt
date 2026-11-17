package com.paperlight.reader.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.roundToInt

internal data class RenderedPage(val bitmap: Bitmap, val index: Int, val count: Int)

internal fun renderPdfPage(path: String, position: Float, pixelWidth: Int): RenderedPage {
    val descriptor = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = try {
        PdfRenderer(descriptor)
    } catch (failure: Exception) {
        descriptor.close()
        throw failure
    }
    renderer.use { pdf ->
        require(pdf.pageCount > 0) { "This PDF has no pages." }
        val index = ((pdf.pageCount - 1) * position.coerceIn(0f, 1f)).roundToInt()
        pdf.openPage(index).use { page ->
            val scale = minOf(pixelWidth.toFloat() / page.width, 2400f / page.height)
            val bitmap = Bitmap.createBitmap(
                (page.width * scale).roundToInt().coerceAtLeast(1),
                (page.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return RenderedPage(bitmap, index, pdf.pageCount)
        }
    }
}
