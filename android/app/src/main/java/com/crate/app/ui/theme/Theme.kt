package com.crate.app.ui.theme

import android.app.Activity
import android.content.Context
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

/**
 * Which scheme [CrateTheme] uses. SYSTEM follows the phone
 * (`isSystemInDarkTheme`); LIGHT/DARK pin it. Persisted in
 * SharedPreferences, cycled from the Console/Install top row.
 */
enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
    ;

    fun next(): ThemeMode = when (this) {
        SYSTEM -> LIGHT
        LIGHT -> DARK
        DARK -> SYSTEM
    }

    companion object {
        private const val KEY = "theme_mode"

        fun load(context: Context): ThemeMode {
            val name = context.getSharedPreferences("crate", Context.MODE_PRIVATE)
                .getString(KEY, SYSTEM.name)
            return try {
                valueOf(name ?: SYSTEM.name)
            } catch (_: IllegalArgumentException) {
                SYSTEM
            }
        }

        fun save(context: Context, mode: ThemeMode) {
            context.getSharedPreferences("crate", Context.MODE_PRIVATE)
                .edit().putString(KEY, mode.name).apply()
        }
    }
}

/**
 * App theme: baseline Material3 light/dark schemes (the familiar purple
 * buttons in both), following the system unless pinned. Also paints the
 * status/navigation bars with the scheme surface and flips icon contrast,
 * and keeps the launch window (`themes.xml` + `-night`) matching so there
 * is no bright flash on cold start.
 */
@Composable
fun CrateTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val useDark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = if (useDark) darkColorScheme() else lightColorScheme()
    val view = LocalView.current
    SideEffect {
        if (!view.isInEditMode) {
            val window = (view.context as Activity).window
            window.statusBarColor = scheme.surface.toArgb()
            window.navigationBarColor = scheme.surface.toArgb()
            window.insetsController?.let { ctrl ->
                val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                ctrl.setSystemBarsAppearance(if (useDark) 0 else mask, mask)
            }
        }
    }
    // Material3 does not set a default content color (bare Text falls back
    // to LocalContentColor's Black default — invisible on dark
    // backgrounds). Pin it to onBackground like Material2 did.
    CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
