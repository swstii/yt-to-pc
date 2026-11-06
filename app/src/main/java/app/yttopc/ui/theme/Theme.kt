package app.yttopc.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Ink = Color(0xFF12140F)
private val Paper = Color(0xFFF4F6EC)
private val Muted = Color(0xFFC9CDB8)
private val Leaf = Color(0xFFE4F27A)
private val LeafInk = Color(0xFF1A1C10)
private val Card = Color(0xF01C1F16)
private val Line = Color(0xFF3A3F32)

private val Colors = darkColorScheme(
    primary = Leaf,
    onPrimary = LeafInk,
    primaryContainer = Color(0xFF2C3318),
    onPrimaryContainer = Leaf,
    secondary = Muted,
    onSecondary = LeafInk,
    background = Ink,
    onBackground = Paper,
    surface = Card,
    onSurface = Paper,
    surfaceVariant = Color(0xFF2A2E24),
    onSurfaceVariant = Muted,
    outline = Line,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun YtTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, shapes = AppShapes, content = content)
}
