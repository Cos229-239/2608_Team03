package com.arv.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Live input meter drawn from the amplitude samples the recording service emits.
 *
 * Its real job is reassurance: an older storyteller and the person holding the phone both
 * need to see, without reading anything, that the microphone is actually hearing them.
 * A flat line means something is wrong and it needs to be obvious immediately.
 */
@Composable
fun Waveform(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
    barColor: Color = MaterialTheme.colorScheme.primary,
    idleColor: Color = MaterialTheme.colorScheme.outline
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                contentDescription = if (amplitudes.any { it > 0.02f }) {
                    "Microphone is picking up sound"
                } else {
                    "No sound detected"
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val barCount = 48
            val window = amplitudes.takeLast(barCount)
            val slotWidth = size.width / barCount
            val barWidth = (slotWidth * 0.55f).coerceAtLeast(2f)
            val centerY = size.height / 2f

            repeat(barCount) { index ->
                val value = window.getOrNull(index - (barCount - window.size)) ?: 0f
                // Amplitude is perceptually compressed; a linear meter looks dead at
                // normal speaking volume.
                val scaled = if (value <= 0f) 0f else Math.pow(value.toDouble(), 0.45).toFloat()
                val half = (scaled * (size.height / 2f) * 0.92f).coerceAtLeast(1.5f)
                val x = index * slotWidth + slotWidth / 2f

                drawLine(
                    color = if (value > 0f) barColor else idleColor,
                    start = Offset(x, centerY - half),
                    end = Offset(x, centerY + half),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/** Formats elapsed milliseconds as m:ss, or h:mm:ss once an interview runs long. */
fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
