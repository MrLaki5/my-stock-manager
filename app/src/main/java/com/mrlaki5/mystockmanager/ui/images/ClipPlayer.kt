package com.mrlaki5.mystockmanager.ui.images

import android.net.Uri
import android.view.LayoutInflater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mrlaki5.mystockmanager.R

/** Plays a clip in place, starting at once; the player is released when this leaves the screen. */
@Composable
fun ClipPlayer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(uri) {
        // Media3 jumps 15 s forward by default, too coarse for stock clips of 5 to 60 seconds.
        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // Backgrounding the app must not leave the clip playing behind another app.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.pause() }

    AndroidView(
        factory = {
            (LayoutInflater.from(it).inflate(R.layout.clip_player, null) as PlayerView).apply {
                this.player = player
                // One clip at a time, so there is nothing to skip to.
                setShowPreviousButton(false)
                setShowNextButton(false)
            }
        },
        modifier = modifier,
    )
}

private const val SEEK_STEP_MS = 5_000L
