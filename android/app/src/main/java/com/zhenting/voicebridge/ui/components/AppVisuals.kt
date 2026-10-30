package com.zhenting.voicebridge.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class AppSymbol { Voice, Chat, Note, Mic, Speaker, Stop, Send, Plus, History, Check }

/** Small decorative symbols drawn locally so the UI needs no icon font or network assets. */
@Composable
fun SymbolIcon(symbol: AppSymbol, modifier: Modifier = Modifier.size(20.dp), color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier) {
        val stroke = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = 1.8f, cap = StrokeCap.Round)
        withTransform({ scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) }) {
            when (symbol) {
                AppSymbol.Voice -> listOf(7f, 14f, 20f, 12f, 6f).forEachIndexed { index, height ->
                    drawLine(color, Offset(4f + index * 4, 12 - height / 2), Offset(4f + index * 4, 12 + height / 2),
                        strokeWidth = 2.4f, cap = StrokeCap.Round)
                }
                AppSymbol.Chat -> drawPath(Path().apply {
                    moveTo(7f, 4f); lineTo(17f, 4f); quadraticTo(21f, 4f, 21f, 8f)
                    lineTo(21f, 14f); quadraticTo(21f, 17f, 17f, 17f)
                    lineTo(11f, 17f); lineTo(6f, 21f); lineTo(6f, 17f)
                    quadraticTo(3f, 17f, 3f, 14f); lineTo(3f, 8f)
                    quadraticTo(3f, 4f, 7f, 4f); close()
                }, color, style = stroke)
                AppSymbol.Note -> {
                    drawRoundRect(color, Offset(5f, 3f), Size(14f, 18f), CornerRadius(3f), style = stroke)
                    line(9f, 8f, 15f, 8f); line(9f, 12f, 15f, 12f); line(9f, 16f, 13f, 16f)
                }
                AppSymbol.Mic -> {
                    drawRoundRect(color, Offset(9f, 3f), Size(6f, 11f), CornerRadius(3f), style = stroke)
                    drawArc(color, 0f, 180f, false, Offset(6f, 6f), Size(12f, 12f), style = stroke)
                    line(12f, 18f, 12f, 21f); line(8f, 21f, 16f, 21f)
                }
                AppSymbol.Speaker -> {
                    drawPath(Path().apply {
                        moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f)
                        lineTo(7f, 15f); lineTo(3f, 15f); close()
                    }, color, style = stroke)
                    drawArc(color, -50f, 100f, false, Offset(7f, 4f), Size(16f, 16f), style = stroke)
                    drawArc(color, -50f, 100f, false, Offset(11f, 8f), Size(8f, 8f), style = stroke)
                }
                AppSymbol.Stop -> drawRoundRect(color, Offset(7f, 7f), Size(10f, 10f), CornerRadius(2f))
                AppSymbol.Send -> {
                    drawPath(Path().apply {
                        moveTo(3f, 10f); lineTo(21f, 3f); lineTo(14f, 21f); lineTo(11f, 13f); close()
                    }, color, style = stroke)
                    line(11f, 13f, 21f, 3f)
                }
                AppSymbol.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
                AppSymbol.History -> {
                    drawCircle(color, 8f, Offset(12f, 12f), style = stroke)
                    line(12f, 7f, 12f, 12f); line(12f, 12f, 16f, 14f)
                }
                AppSymbol.Check -> drawPath(Path().apply {
                    moveTo(5f, 12f); lineTo(10f, 17f); lineTo(20f, 7f)
                }, color, style = stroke)
            }
        }
    }
}

@Composable
fun BrandMark(size: Dp = 44.dp) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f))
        .background(Brush.linearGradient(listOf(colors.primary, colors.primary.copy(alpha = 0.8f)))),
        contentAlignment = Alignment.Center) {
        SymbolIcon(AppSymbol.Voice, Modifier.size(size * 0.54f), colors.onPrimary)
    }
}

@Composable
fun EmptyState(symbol: AppSymbol, title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center) {
            SymbolIcon(symbol, Modifier.size(28.dp), MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(description, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
