package com.mrlaki5.mystockmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mrlaki5.mystockmanager.data.db.entity.AlbumState
import com.mrlaki5.mystockmanager.data.db.entity.ImageEntity
import kotlin.math.ceil

private const val DAY_MS = 24 * 60 * 60 * 1000.0

/** Null while the album file is where it should be. */
fun ImageEntity.albumLabel(now: Long = System.currentTimeMillis()): String? = when (albumState) {
    AlbumState.PRESENT -> null
    AlbumState.TRASHED -> {
        val days = trashExpiresAt?.let { ceil((it - now) / DAY_MS).toInt() }
        if (days == null || days <= 0) "In trash" else "In trash · $days day${if (days == 1) "" else "s"}"
    }
    AlbumState.MISSING -> "Missing from album"
}

@Composable
fun AlbumBadge(image: ImageEntity, modifier: Modifier = Modifier) {
    val label = image.albumLabel() ?: return
    val trashed = image.albumState == AlbumState.TRASHED
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                if (trashed) Icons.Outlined.Delete else Icons.Outlined.ImageNotSupported,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
            Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        if (trashed) {
            Text("Tap to restore", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
        }
    }
}
