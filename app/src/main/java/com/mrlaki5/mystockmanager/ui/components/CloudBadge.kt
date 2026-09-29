package com.mrlaki5.mystockmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mrlaki5.mystockmanager.nextcloud.CloudMark

fun CloudMark.label(): String = when (this) {
    CloudMark.SYNCED -> "On NextCloud"
    CloudMark.PENDING -> "Waiting to upload to NextCloud"
    CloudMark.FAILED -> "NextCloud refused this file"
}

private fun CloudMark.icon(): ImageVector = when (this) {
    CloudMark.SYNCED -> Icons.Default.CloudDone
    CloudMark.PENDING -> Icons.Default.CloudUpload
    CloudMark.FAILED -> Icons.Default.CloudOff
}

fun CloudMark.tint(): Color = when (this) {
    CloudMark.SYNCED -> Color(0xFF81C784)
    CloudMark.PENDING -> Color.White
    CloudMark.FAILED -> Color(0xFFFF8A80)
}

/** A dark disc behind the icon keeps it readable on any thumbnail. */
@Composable
fun CloudBadge(mark: CloudMark, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(mark.icon(), contentDescription = mark.label(), tint = mark.tint(), modifier = Modifier.size(16.dp))
    }
}
