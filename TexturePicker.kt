package com.paperlight.reader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun TexturePicker(selected: String, paper: Color, ink: Color, onSelect: (String) -> Unit) {
    Text("Paper texture", style = MaterialTheme.typography.labelLarge)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(listOf("none" to "Plain", "grain" to "Grain", "linen" to "Linen", "grid" to "Grid")) { (value, label) ->
            Column(Modifier.width(76.dp).clickable { onSelect(value) }) {
                Canvas(Modifier.fillMaxWidth().height(86.dp).border(if (selected == value) 2.dp else 1.dp,
                    if (selected == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                    drawRect(paper)
                    val color = ink.copy(alpha = .13f)
                    if (value == "grain") {
                        repeat(420) { index ->
                            val x = ((index * 73) % 431) / 431f * size.width
                            val y = ((index * 151) % 433) / 433f * size.height
                            drawCircle(color, .45.dp.toPx(), Offset(x, y))
                        }
                    } else if (value != "none") {
                        val step = if (value == "grid") 20.dp.toPx() else 4.dp.toPx()
                        var y = 0f
                        while (y < size.height) { drawLine(color, Offset(0f, y), Offset(size.width, y)); y += step }
                        var x = 0f
                        while (x < size.width) { drawLine(color, Offset(x, 0f), Offset(x, size.height)); x += step }
                    }
                }
                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}
