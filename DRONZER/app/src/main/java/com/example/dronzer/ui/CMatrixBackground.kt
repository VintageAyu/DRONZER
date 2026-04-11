package com.example.dronzer.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import kotlin.random.Random

/**
 * Authentic, Grid-Locked Matrix Digital Rain Effect.
 */
@Composable
fun CMatrixBackground(
    modifier: Modifier = Modifier,
    matrixColor: Color = Color(0xFF00FF41),
    chars: String = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",
    fontSize: TextUnit = 14.sp
) {
    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSize.toPx() }

    val time = produceState(initialValue = 0L) {
        while (isActive) {
            withFrameMillis { frameTime -> value = frameTime }
        }
    }

    val tailPaint = remember(matrixColor, fontSizePx) {
        Paint().apply {
            this.color = matrixColor.toArgb()
            this.textSize = fontSizePx
            this.typeface = Typeface.MONOSPACE
            this.isAntiAlias = true
        }
    }

    val headPaint = remember(fontSizePx) {
        Paint().apply {
            this.color = Color.White.toArgb()
            this.textSize = fontSizePx
            this.typeface = Typeface.MONOSPACE
            this.isAntiAlias = true
        }
    }

    val charWidth = remember(tailPaint) { tailPaint.measureText("W") }
    val columnWidth = charWidth * 1.2f
    val rowHeight = fontSizePx

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        val columnsCount = ((widthPx / columnWidth).toInt() + 1).coerceAtLeast(1)
        val rowsCount = ((heightPx / rowHeight).toInt() + 1).coerceAtLeast(1)

        val startXOffset = (widthPx - (columnsCount * columnWidth)) / 2f

        val columns = remember(columnsCount, rowsCount) {
            Array(columnsCount) { col ->
                val gridChars = CharArray(rowsCount) { chars.random() }
                val dropYs = FloatArray(1) { Random.nextFloat() * heightPx * 2f - heightPx }
                val dropSpeeds = FloatArray(1) { Random.nextFloat() * 10f + 5f }
                val dropLengths = IntArray(1) { Random.nextInt(10, 30) }

                MatrixColumn(startXOffset + col * columnWidth, gridChars, dropYs, dropSpeeds, dropLengths)
            }
        }

        val lastTimeRef = remember { mutableLongStateOf(0L) }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val currentTime = time.value
            val deltaMs = if (lastTimeRef.longValue == 0L) 16f else (currentTime - lastTimeRef.longValue).toFloat().coerceAtMost(50f)
            lastTimeRef.longValue = currentTime

            val timeScale = deltaMs / 16.66f

            drawIntoCanvas { canvas ->
                columns.forEach { column ->
                    if (Random.nextFloat() < 0.02f) {
                        column.chars[Random.nextInt(rowsCount)] = chars.random()
                    }

                    for (d in column.dropYs.indices) {
                        column.dropYs[d] += column.dropSpeeds[d] * timeScale

                        if (column.dropYs[d] > heightPx + (column.dropLengths[d] * rowHeight)) {
                            column.dropYs[d] = -column.dropLengths[d] * rowHeight
                            column.dropSpeeds[d] = Random.nextFloat() * 10f + 5f
                        }
                    }

                    for (row in 0 until rowsCount) {
                        var maxAlphaRatio = 0f
                        var isHead = false

                        for (d in column.dropYs.indices) {
                            val dropExactRow = column.dropYs[d] / rowHeight
                            val distanceInRows = dropExactRow - row

                            if (distanceInRows in 0f..<column.dropLengths[d].toFloat()) {
                                val alphaRatio = 1f - (distanceInRows / column.dropLengths[d])
                                if (alphaRatio > maxAlphaRatio) maxAlphaRatio = alphaRatio
                                if (distanceInRows < 1f) isHead = true
                            }
                        }

                        if (maxAlphaRatio > 0f) {
                            val charStr = column.chars[row].toString()
                            val drawY = (row + 1) * rowHeight

                            if (isHead) {
                                canvas.nativeCanvas.drawText(charStr, column.x, drawY, headPaint)
                            } else {
                                tailPaint.alpha = (maxAlphaRatio * 255).toInt()
                                canvas.nativeCanvas.drawText(charStr, column.x, drawY, tailPaint)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScanlineOverlay(color: Color = Color.Green) {
    val infiniteTransition = rememberInfiniteTransition(label = "scanline")
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanline_pos"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val scanlineY = offsetY * size.height
        
        // Horizontal moving line
        drawLine(
            color = color.copy(alpha = 0.15f),
            start = Offset(0f, scanlineY),
            end = Offset(size.width, scanlineY),
            strokeWidth = 2.dp.toPx()
        )
        
        // Static scanlines
        val lineSpacing = 3.dp.toPx()
        for (i in 0..(size.height / lineSpacing).toInt()) {
            drawLine(
                color = Color.Black.copy(alpha = 0.3f),
                start = Offset(0f, i * lineSpacing),
                end = Offset(size.width, i * lineSpacing),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

class MatrixColumn(
    val x: Float,
    val chars: CharArray,
    val dropYs: FloatArray,
    val dropSpeeds: FloatArray,
    val dropLengths: IntArray
)
