package app.yttopc.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Movie
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.max

private const val BACKGROUND = "shaq-fist-pump.gif"

@Composable
fun GifBackdrop(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ShaqBackdropView(it) },
    )
}

@Suppress("DEPRECATION")
private class ShaqBackdropView(context: Context) : View(context) {
    private var movie: Movie? = null
    private var startedAt = 0L

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        Thread {
            val decoded = context.assets.open(BACKGROUND).use(Movie::decodeStream)
            post {
                movie = decoded
                startedAt = SystemClock.uptimeMillis()
                invalidate()
            }
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        val current = movie
        if (current == null || width == 0 || height == 0 || current.width() <= 0 || current.height() <= 0) return
        val duration = current.duration().coerceAtLeast(1)
        val elapsed = ((SystemClock.uptimeMillis() - startedAt) % duration).toInt()
        current.setTime(elapsed)
        val fitted = max(width / current.width().toFloat(), height / current.height().toFloat()) * 0.78f
        val dx = (width - current.width() * fitted) / 2f
        val dy = (height - current.height() * fitted) / 2f
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(fitted, fitted)
        current.draw(canvas, 0f, 0f)
        canvas.restore()
        postInvalidateOnAnimation()
    }
}
