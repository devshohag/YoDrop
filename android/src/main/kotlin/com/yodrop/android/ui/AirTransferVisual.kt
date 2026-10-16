package com.yodrop.android.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Code-drawn artwork keeps the APK small; animation never authorizes a transfer. */
@Composable
fun AirTransferVisual(waiting: Boolean, progress: Float?, done: Boolean, catchReady: Boolean) {
    val pulse = if (waiting) {
        val transition = rememberInfiniteTransition(label = "nearby pulse")
        val scale by transition.animateFloat(.9f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "ring")
        scale
    } else 1f
    val completion by animateFloatAsState(if (done) 1f else progress?.coerceIn(0f, 1f) ?: 0f, label = "delivery")
    val color = MaterialTheme.colorScheme.primary
    val centerColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(156.dp).semantics {
        contentDescription = when { done -> "Files delivered"; progress != null -> "Transfer in progress"; catchReady -> "Ready to catch"; waiting -> "Waiting nearby"; else -> "Ready to share files" }
    }) {
        val c = center; val radius = size.height * .43f
        drawCircle(color.copy(alpha = .05f), radius * pulse, c)
        drawCircle(color.copy(alpha = .16f), radius * pulse, c, style = Stroke(1.dp.toPx()))
        drawCircle(color.copy(alpha = .12f), radius * .75f, c, style = Stroke(1.dp.toPx()))
        drawCircle(centerColor, radius * .55f, c)
        if (completion > 0) drawArc(color, -90f, completion * 360f, false,
            Offset(c.x - radius, c.y - radius), Size(radius * 2, radius * 2), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        if (done) {
            drawLine(color, Offset(c.x - 15.dp.toPx(), c.y), Offset(c.x - 3.dp.toPx(), c.y + 11.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
            drawLine(color, Offset(c.x - 3.dp.toPx(), c.y + 11.dp.toPx()), Offset(c.x + 18.dp.toPx(), c.y - 12.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
        } else {
            val w = 27.dp.toPx(); val h = 34.dp.toPx()
            drawRoundRect(color, Offset(c.x - w / 2, c.y - h / 2), Size(w, h), CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx()))
            repeat(3) { n -> drawLine(color.copy(alpha = .75f), Offset(c.x - w * .25f, c.y - h * .15f + n * 7.dp.toPx()),
                Offset(c.x + w * .25f, c.y - h * .15f + n * 7.dp.toPx()), 2.dp.toPx(), StrokeCap.Round) }
        }
    }
}
