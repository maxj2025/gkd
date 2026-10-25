package li.songe.gkd.ui.style

import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import li.songe.gkd.app
import li.songe.gkd.store.storeFlow
import li.songe.gkd.ui.share.LocalDarkTheme
import li.songe.gkd.ui.share.LocalIsTalkbackEnabled
import li.songe.gkd.util.AndroidTarget

// 清屏品牌色：以「青」为主色，蓝青为辅色，整体偏冷、干净
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006B5F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA6F2E2),
    onPrimaryContainer = Color(0xFF00201C),
    inversePrimary = Color(0xFF82D5C5),
    secondary = Color(0xFF4A635E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE8E1),
    onSecondaryContainer = Color(0xFF06201B),
    tertiary = Color(0xFF3D6481),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC9E6FF),
    onTertiaryContainer = Color(0xFF001E2F),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF4FAF8),
    onBackground = Color(0xFF161D1B),
    surface = Color(0xFFF4FAF8),
    onSurface = Color(0xFF161D1B),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    surfaceTint = Color(0xFF006B5F),
    inverseSurface = Color(0xFF2B3230),
    inverseOnSurface = Color(0xFFECF2EF),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBEC9C5),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF4FAF8),
    surfaceDim = Color(0xFFD5DBD9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEEF5F2),
    surfaceContainer = Color(0xFFE8EFEC),
    surfaceContainerHigh = Color(0xFFE2E9E6),
    surfaceContainerHighest = Color(0xFFDDE4E1),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7FDCCA),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFFA6F2E2),
    inversePrimary = Color(0xFF006B5F),
    secondary = Color(0xFFB1CCC5),
    onSecondary = Color(0xFF1C3530),
    secondaryContainer = Color(0xFF334B46),
    onSecondaryContainer = Color(0xFFCDE8E1),
    tertiary = Color(0xFFA5CBEE),
    onTertiary = Color(0xFF0B3450),
    tertiaryContainer = Color(0xFF244B68),
    onTertiaryContainer = Color(0xFFC9E6FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0D1412),
    onBackground = Color(0xFFDDE4E1),
    surface = Color(0xFF0D1412),
    onSurface = Color(0xFFDDE4E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C5),
    surfaceTint = Color(0xFF7FDCCA),
    inverseSurface = Color(0xFFDDE4E1),
    inverseOnSurface = Color(0xFF2B3230),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4946),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF333A38),
    surfaceDim = Color(0xFF0D1412),
    surfaceContainerLowest = Color(0xFF080F0D),
    surfaceContainerLow = Color(0xFF161D1B),
    surfaceContainer = Color(0xFF1A211F),
    surfaceContainerHigh = Color(0xFF242B29),
    surfaceContainerHighest = Color(0xFF2F3634),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val AppTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun AppTheme(
    invertedTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val enableDarkThemeFlow = remember {
        storeFlow.map { it.enableDarkTheme }.debounce(300).stateIn(
            scope, SharingStarted.Eagerly, storeFlow.value.enableDarkTheme
        )
    }
    val enableDynamicColorFlow = remember {
        storeFlow.map { it.enableDynamicColor }.debounce(300).stateIn(
            scope, SharingStarted.Eagerly, storeFlow.value.enableDynamicColor
        )
    }
    val enableDarkTheme by enableDarkThemeFlow.collectAsState()
    val enableDynamicColor by enableDynamicColorFlow.collectAsState()
    val systemInDarkTheme = isSystemInDarkTheme()
    val darkTheme = (enableDarkTheme ?: systemInDarkTheme).let {
        if (invertedTheme) !it else it
    }
    val colorScheme = when {
        AndroidTarget.S && enableDynamicColor && darkTheme -> dynamicDarkColorScheme(app)
        AndroidTarget.S && enableDynamicColor && !darkTheme -> dynamicLightColorScheme(app)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val activity = LocalActivity.current
    if (activity != null) {
        LaunchedEffect(darkTheme) {
            // https://github.com/gkd-kit/gkd/pull/421
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
            }
        }
        val bg = colorScheme.background.toArgb()
        LaunchedEffect(darkTheme, bg) {
            activity.window.decorView.setBackgroundColor(bg)
        }
    }

    var isTalkbackEnabled by remember { mutableStateOf(app.a11yManager.isTouchExplorationEnabled) }
    DisposableEffect(null) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener {
            isTalkbackEnabled = it
        }
        app.a11yManager.addTouchExplorationStateChangeListener(listener)
        onDispose {
            app.a11yManager.removeTouchExplorationStateChangeListener(listener)
        }
    }
    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalIsTalkbackEnabled provides isTalkbackEnabled
    ) {
        MaterialTheme(
            colorScheme = colorScheme.animation(),
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}

@Composable
private fun Color.animation() = animateColorAsState(
    targetValue = this,
    animationSpec = tween(durationMillis = 500),
    label = "animation"
).value

@Composable
private fun ColorScheme.animation(): ColorScheme {
    return copy(
        primary = primary.animation(),
        onPrimary = onPrimary.animation(),
        primaryContainer = primaryContainer.animation(),
        onPrimaryContainer = onPrimaryContainer.animation(),
        inversePrimary = inversePrimary.animation(),
        secondary = secondary.animation(),
        onSecondary = onSecondary.animation(),
        secondaryContainer = secondaryContainer.animation(),
        onSecondaryContainer = onSecondaryContainer.animation(),
        tertiary = tertiary.animation(),
        onTertiary = onTertiary.animation(),
        tertiaryContainer = tertiaryContainer.animation(),
        onTertiaryContainer = onTertiaryContainer.animation(),
        background = background.animation(),
        onBackground = onBackground.animation(),
        surface = surface.animation(),
        onSurface = onSurface.animation(),
        surfaceVariant = surfaceVariant.animation(),
        onSurfaceVariant = onSurfaceVariant.animation(),
        surfaceTint = surfaceTint.animation(),
        inverseSurface = inverseSurface.animation(),
        inverseOnSurface = inverseOnSurface.animation(),
        error = error.animation(),
        onError = onError.animation(),
        errorContainer = errorContainer.animation(),
        onErrorContainer = onErrorContainer.animation(),
        outline = outline.animation(),
        outlineVariant = outlineVariant.animation(),
        scrim = scrim.animation(),
        surfaceBright = surfaceBright.animation(),
        surfaceDim = surfaceDim.animation(),
        surfaceContainer = surfaceContainer.animation(),
        surfaceContainerHigh = surfaceContainerHigh.animation(),
        surfaceContainerHighest = surfaceContainerHighest.animation(),
        surfaceContainerLow = surfaceContainerLow.animation(),
        surfaceContainerLowest = surfaceContainerLowest.animation(),
    )
}
