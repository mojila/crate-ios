package com.crate.app

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crate.app.ui.SectionCard
import com.crate.app.ui.TipIconButton
import com.crate.app.ui.theme.ThemeMode

/**
 * Settings screen: appearance (theme pin), VM prefs, + about. The theme
 * previously lived as a top-row cycler; it moved here so the menu is
 * Home/Install/Settings with Home selected by default.
 */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    displayAtBoot: Boolean,
    onDisplayAtBoot: (Boolean) -> Unit,
    engineVer: String,
) {
    val context = LocalContext.current
    val version = remember {
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            pi.versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()),
    ) {
        SectionCard("Appearance") {
        for (mode in ThemeMode.entries) {
            val desc = when (mode) {
                ThemeMode.SYSTEM -> "Follow system"
                ThemeMode.LIGHT -> "Always light"
                ThemeMode.DARK -> "Always dark"
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .clickable { onThemeMode(mode) }
                    .padding(vertical = 4.dp),
            ) {
                Icon(
                    imageVector = if (mode == themeMode) Icons.Filled.RadioButtonChecked
                        else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = desc,
                )
                Column {
                    Text(mode.label, fontSize = 14.sp)
                    Text(desc, fontSize = 12.sp)
                }
            }
        }
        }

        SectionCard("Virtual machine") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .clickable { onDisplayAtBoot(!displayAtBoot) }
                .padding(vertical = 4.dp),
        ) {
            Checkbox(
                checked = displayAtBoot,
                onCheckedChange = onDisplayAtBoot,
            )
            Column {
                Text("Display (GPU) at boot", fontSize = 14.sp)
                Text("Off is the lean serial-console path", fontSize = 12.sp)
            }
        }
        }

        SectionCard("About") {
        Text("Crate $version ($engineVer)", fontSize = 14.sp)
        }

        SectionCard("Diagnostics") {
        // Crash + engine-error log: uncaught exceptions land here even with
        // no cable attached. Share sends the newest entries as text.
        var logStamp by remember { mutableStateOf(0L) }
        val logFile = remember(logStamp) { CrashLog.logFile(context) }
        val logSize = remember(logStamp) {
            try {
                if (logFile.exists()) logFile.length() else -1L
            } catch (_: Exception) {
                -1L
            }
        }
        Text(
            if (logSize < 0) "Log empty — crashes and VM errors land here"
            else "Log ${(logSize / 1024).coerceAtLeast(1)} KiB — crashes and VM errors",
            fontSize = 14.sp,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TipIconButton(
                icon = Icons.Filled.Share,
                tip = "Share log",
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "crate log")
                        putExtra(Intent.EXTRA_TEXT,
                            CrashLog.tail(context).ifEmpty { "(log empty)" })
                    }
                    context.startActivity(Intent.createChooser(send, "Share crate log"))
                },
                enabled = logSize >= 0,
            )
            TipIconButton(
                icon = Icons.Filled.Delete,
                tip = "Clear log",
                onClick = {
                    CrashLog.clear(context)
                    logStamp = System.currentTimeMillis()
                },
                enabled = logSize >= 0,
            )
        }
        }
    }
}
