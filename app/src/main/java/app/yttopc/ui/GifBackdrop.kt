package app.yttopc.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private const val BACKGROUND = "background.mp4"

@Composable
fun GifBackdrop(modifier: Modifier = Modifier, speed: Float = 1f) {
    LoopVideo(BACKGROUND, modifier.fillMaxSize(), cover = true, speed = speed)
}

@Composable
fun LoopVideo(asset: String, modifier: Modifier = Modifier, cover: Boolean = false, speed: Float = 1f) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val viewRef = remember { mutableStateOf<LoopVideoView?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { LoopVideoView(it, asset, cover, speed).also { view -> viewRef.value = view } },
        update = { it.setSpeed(speed) },
    )
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewRef.value?.resumePlayback()
                Lifecycle.Event.ON_PAUSE -> viewRef.value?.pausePlayback()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

private class LoopVideoView(
    context: Context,
    private val asset: String,
    private val cover: Boolean,
    speed: Float,
) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var output: Surface? = null
    private var videoW = 0
    private var videoH = 0
    private var playWhenReady = true
    private var speed = speed

    init {
        surfaceTextureListener = this
        isOpaque = cover
    }

    fun pausePlayback() {
        playWhenReady = false
        player?.let { runCatching { if (it.isPlaying) it.pause() } }
    }

    fun resumePlayback() {
        playWhenReady = true
        player?.let { runCatching { if (!it.isPlaying) it.start() } }
    }

    fun setSpeed(value: Float) {
        speed = value
        player?.let { applySpeed(it) }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        open(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        applyFit()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        releasePlayer()
        super.onDetachedFromWindow()
    }

    private fun open(texture: SurfaceTexture) {
        releasePlayer()
        val next = MediaPlayer()
        player = next
        try {
            context.assets.openFd(asset).use { fd ->
                next.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            output = Surface(texture).also(next::setSurface)
            next.isLooping = true
            next.setVolume(0f, 0f)
            next.setOnVideoSizeChangedListener { _, w, h ->
                videoW = w
                videoH = h
                applyFit()
            }
            next.setOnPreparedListener {
                runCatching {
                    videoW = it.videoWidth
                    videoH = it.videoHeight
                    applyFit()
                    applySpeed(it)
                    if (playWhenReady) it.start()
                }
            }
            next.setOnErrorListener { _, _, _ ->
                releasePlayer()
                true
            }
            next.prepareAsync()
        } catch (_: Exception) {
            releasePlayer()
        }
    }

    private fun applySpeed(player: MediaPlayer) {
        runCatching { player.playbackParams = PlaybackParams().setSpeed(speed) }
    }

    private fun applyFit() {
        if (videoW <= 0 || videoH <= 0 || width <= 0 || height <= 0) return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val fitX = viewW / videoW
        val fitY = viewH / videoH
        val scale = if (cover) maxOf(fitX, fitY) else minOf(fitX, fitY)
        val matrix = Matrix()
        matrix.setScale(scale / fitX, scale / fitY, viewW / 2f, viewH / 2f)
        setTransform(matrix)
    }

    private fun releasePlayer() {
        player?.let { current ->
            player = null
            current.setOnPreparedListener(null)
            current.setOnVideoSizeChangedListener(null)
            runCatching { current.reset() }
            runCatching { current.release() }
        }
        output?.let { runCatching { it.release() } }
        output = null
    }
}
