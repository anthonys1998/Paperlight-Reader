package com.paperlight.reader.ui.reader

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

/** Animate a viewport bitmap, so chapter size cannot affect the fold's frame cost. */
@Composable
internal fun ChapterTurnSheet(bitmap: Bitmap, progress: Float, direction: Int, paper: Color, modifier: Modifier) {
    Canvas(modifier) {
        val canvas = drawContext.canvas.nativeCanvas
        val width = size.width
        val height = size.height
        val p = progress.coerceIn(0f, 1f)
        val edge = width * (1f - p)
        val foldWidth = width * .16f * kotlin.math.sin(Math.PI * p).toFloat()
        fun x(value: Float) = if (direction > 0) value else width - value
        val visible = Path().apply {
            moveTo(x(0f), 0f)
            lineTo(x((edge + foldWidth).coerceIn(0f, width)), 0f)
            lineTo(x((edge - foldWidth).coerceIn(0f, width)), height)
            lineTo(x(0f), height)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.save()
        canvas.clipPath(visible)
        canvas.drawBitmap(bitmap, null, RectF(0f, 0f, width, height), paint)
        canvas.restore()
        if (foldWidth > .5f) {
            val back = Path().apply {
                moveTo(x((edge + foldWidth).coerceIn(0f, width)), 0f)
                lineTo(x((edge - foldWidth * 1.8f).coerceIn(0f, width)), height * .76f)
                lineTo(x((edge - foldWidth).coerceIn(0f, width)), height)
                close()
            }
            paint.color = paper.toArgb()
            canvas.drawPath(back, paint)
            paint.shader = LinearGradient(x(edge - foldWidth * 1.8f), 0f, x(edge + foldWidth), 0f,
                intArrayOf(0x44000000, 0x00000000, 0x22000000), null, Shader.TileMode.CLAMP)
            canvas.drawPath(back, paint)
        }
    }
}
