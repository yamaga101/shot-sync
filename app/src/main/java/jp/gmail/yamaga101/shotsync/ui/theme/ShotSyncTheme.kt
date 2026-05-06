package jp.gmail.yamaga101.shotsync.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// shot-sync brand: teal/green。アイコンの gradient と揃える。
// Drive を意識した「アップロード成功感」のあるトーン。
private val Teal40 = Color(0xFF1A8E94)
private val Teal30 = Color(0xFF005F62)
private val Teal80 = Color(0xFF74D6DA)
private val Teal90 = Color(0xFFA0F1F5)
private val Green40 = Color(0xFF2BB673)
private val Green80 = Color(0xFF8FE7BB)
private val Amber50 = Color(0xFFE0A800)

private val LightColors = lightColorScheme(
    primary = Teal40,
    onPrimary = Color.White,
    primaryContainer = Teal90,
    onPrimaryContainer = Color(0xFF002021),
    secondary = Green40,
    onSecondary = Color.White,
    secondaryContainer = Green80,
    onSecondaryContainer = Color(0xFF002112),
    tertiary = Amber50,
    onTertiary = Color.White,
    background = Color(0xFFF7FBFA),
    onBackground = Color(0xFF0E1414),
    surface = Color(0xFFFCFEFC),
    onSurface = Color(0xFF0E1414),
    surfaceVariant = Color(0xFFD9E5E3),
    onSurfaceVariant = Color(0xFF3D4847),
    outline = Color(0xFF6E7878),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Teal80,
    onPrimary = Color(0xFF003234),
    primaryContainer = Teal30,
    onPrimaryContainer = Teal90,
    secondary = Green80,
    onSecondary = Color(0xFF003820),
    secondaryContainer = Color(0xFF005233),
    onSecondaryContainer = Green80,
    tertiary = Color(0xFFFFD86C),
    onTertiary = Color(0xFF402D00),
    background = Color(0xFF0E1414),
    onBackground = Color(0xFFE0E3E2),
    surface = Color(0xFF121A19),
    onSurface = Color(0xFFE0E3E2),
    surfaceVariant = Color(0xFF3D4847),
    onSurfaceVariant = Color(0xFFBEC9C7),
    outline = Color(0xFF889291),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

@Composable
fun ShotSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Material You dynamic color (Android 12+) を使うか。
     *  shot-sync ブランドを優先したいので default は false。 */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
