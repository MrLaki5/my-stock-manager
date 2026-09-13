package com.mrlaki5.mystockmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mrlaki5.mystockmanager.data.db.entity.ImageState

/** Colour and wording for each pipeline state, kept in one place so the list and grid agree. */
fun ImageState.label(): String = when (this) {
    ImageState.IMPORTED -> "Unfiled"
    ImageState.FILED -> "No metadata"
    ImageState.GENERATING -> "Generating"
    ImageState.GENERATED -> "Generated"
    ImageState.GENERATION_FAILED -> "Failed"
}

fun ImageState.color(): Color = when (this) {
    ImageState.IMPORTED -> Color(0xFF757575)
    ImageState.FILED -> Color(0xFF757575)
    ImageState.GENERATING -> Color(0xFF1565C0)
    ImageState.GENERATED -> Color(0xFF2E7D32)
    ImageState.GENERATION_FAILED -> Color(0xFFC62828)
}

@Composable
fun ImageStateBadge(state: ImageState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(state.color().copy(alpha = 0.92f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state == ImageState.GENERATING) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = Color.White,
            )
        }
        Text(
            text = state.label(),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
