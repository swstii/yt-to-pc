package app.yttopc.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.view.View
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
private const val FRAME_MS = 80L

@Composable
fun GifBackdrop(modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val viewRef = remember { mutableStateOf<VideoBackdropView?>(null) }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { VideoBackdropView(it).also { view -> viewRef.value = view } },
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

private class VideoBackdropView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var output: Surface? = null
    private var videoW = 0
    private var videoH = 0
    private var playWhenReady = true

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    fun pausePlayback() {
        playWhenReady = false
        player?.let { runCatching { if (it.isPlaying) it.pause() } }
    }

    fun resumePlayback() {
        playWhenReady = true
        player?.let { runCatching { if (!it.isPlaying) it.start() } }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        open(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        applyCover()
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
            context.assets.openFd(BACKGROUND).use { fd ->
                next.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            output = Surface(texture).also(next::setSurface)
            next.isLooping = true
            next.setVolume(0f, 0f)
            next.setOnVideoSizeChangedListener { _, w, h ->
                videoW = w
                videoH = h
                applyCover()
            }
            next.setOnPreparedListener {
                videoW = it.videoWidth
                videoH = it.videoHeight
                applyCover()
                if (playWhenReady) it.start()
            }
            next.prepareAsync()
        } catch (_: Exception) {
            releasePlayer()
        }
    }

    private fun applyCover() {
        if (videoW <= 0 || videoH <= 0 || width <= 0 || height <= 0) return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val fitX = viewW / videoW
        val fitY = viewH / videoH
        val cover = maxOf(fitX, fitY)
        val matrix = Matrix()
        matrix.setScale(cover / fitX, cover / fitY, viewW / 2f, viewH / 2f)
        setTransform(matrix)
    }

    private fun releasePlayer() {
        player?.let { current ->
            player = null
            current.setOnPreparedListener(null)
            current.setOnVideoSizeChangedListener(null)
            runCatching { current.reset() }
            current.release()
        }
        output?.release()
        output = null
    }
}

@Composable
fun AssetGif(asset: String, modifier: Modifier = Modifier, cover: Boolean = false) {
    AndroidView(
        modifier = modifier,
        factory = { GifView(it, asset, cover) },
    )
}

@Suppress("DEPRECATION")
private class GifView(context: Context, asset: String, private val cover: Boolean) : View(context) {
    private var movie: Movie? = null
    private var startedAt = 0L
    private var frame: Bitmap? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { drawFrame() }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dest = RectF()

    init {
        Thread {
            val decoded = context.assets.open(asset).use(Movie::decodeStream)
            post {
                movie = decoded
                startedAt = SystemClock.uptimeMillis()
                drawFrame()
            }
        }.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val (bw, bh) = bufferSize(w, h)
        if (frame?.width != bw || frame?.height != bh) frame = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    }

    /** Wallpaper is decoded at half the screen, then scaled up. The settings GIF stays 1:1. */
    private fun bufferSize(w: Int, h: Int): Pair<Int, Int> {
        if (!cover) return w to h
        return (w / 2).coerceAtLeast(1) to (h / 2).coerceAtLeast(1)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = frame ?: return
        dest.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(bitmap, null, dest, paint)
    }

    private fun drawFrame() {
        val current = movie
        val bitmap = frame
        if (current == null || bitmap == null || current.width() <= 0 || current.height() <= 0) {
            handler.postDelayed(tick, FRAME_MS)
            return
        }
        val duration = current.duration().coerceAtLeast(1)
        current.setTime(((SystemClock.uptimeMillis() - startedAt) % duration).toInt())
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val scaleX = bw / current.width()
        val scaleY = bh / current.height()
        val fitted = if (cover) maxOf(scaleX, scaleY) else minOf(scaleX, scaleY)
        val dx = (bw - current.width() * fitted) / 2f
        val dy = (bh - current.height() * fitted) / 2f
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(fitted, fitted)
        current.draw(canvas, 0f, 0f)
        canvas.restore()
        invalidate()
        handler.postDelayed(tick, FRAME_MS)
    }
}
