package com.crate.app

import android.content.Context

/**
 * Persistent VM prefs (SharedPreferences file "crate", same as
 * [com.crate.app.ui.theme.ThemeMode]): whether boots register the virtio
 * GPU + tablet/keyboard inputs. Default on — a desktop guest needs its
 * screen; off is the lean serial-console path.
 */
object VmPrefs {
    private const val KEY_DISPLAY_AT_BOOT = "display_at_boot"

    fun loadDisplayAtBoot(context: Context): Boolean {
        return context.getSharedPreferences("crate", Context.MODE_PRIVATE)
            .getBoolean(KEY_DISPLAY_AT_BOOT, true)
    }

    fun saveDisplayAtBoot(context: Context, on: Boolean) {
        context.getSharedPreferences("crate", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DISPLAY_AT_BOOT, on).apply()
    }
}
