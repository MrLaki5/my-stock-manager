package com.mrlaki5.mystockmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Minutes and seconds, as a video player shows them. */
fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs + 500) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** Marks a clip in the grid; the same dark backing as [CloudBadge] keeps it readable on any frame. */
@Composable
fun DurationBadge(durationMs: Long?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(start = 2.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = "Video", tint = Color.White, modifier = Modifier.size(16.dp))
        durationMs?.let { Text(formatDuration(it), color = Color.White, style = MaterialTheme.typography.labelSmall) }
    }
}
