package com.music.bitchord.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import com.music.bitchord.ui.player.CanvasVideoSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The engine owns the player; surfaces only subscribe to its frames. */
@Composable
internal fun DesktopNativeVideo(spec: CanvasVideoSpec, modifier: Modifier) {
    val source by DesktopVideoPlayback.frames.collectAsState()
    var frame by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    val onRendered by rememberUpdatedState(spec.onRenderedChanged)
    val onCover by rememberUpdatedState(spec.onCoverChanged)
    val onCapture by rememberUpdatedState(spec.onFrameCaptured)
    val onAspect by rememberUpdatedState(spec.onAspectRatioChanged)
    LaunchedEffect(source) {
        source?.frames?.collect { image ->
            frame = withContext(Dispatchers.Default) { image?.toComposeImageBitmap() }
        }
    }
    LaunchedEffect(source) {
        source?.aspectRatio?.collect { aspect -> aspect?.let(onAspect) }
    }
    val rendered = frame != null
    LaunchedEffect(rendered) { onRendered(rendered); onCover(if (rendered) 1f else 0f) }
    LaunchedEffect(source, spec.refreshFrameEveryMs) {
        if (source == null) return@LaunchedEffect
        while (true) {
            frame?.let(onCapture)
            kotlinx.coroutines.delay(spec.refreshFrameEveryMs ?: 1000L)
        }
    }
    DisposableEffect(Unit) { onDispose { onRendered(false); onCover(0f) } }
    Box(modifier.graphicsLayer { alpha = spec.presentationAlpha(); clip = true }
        .onSizeChanged { source?.setTargetSize(it.width, it.height) }, contentAlignment = Alignment.Center) {
        frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}
