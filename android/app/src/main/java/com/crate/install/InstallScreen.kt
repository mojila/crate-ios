package com.crate.install

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import com.crate.app.ui.SectionCard
import com.crate.app.ui.TipIconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Install phase UI: catalog picking + install flow. Installed machines live
 * on Home; when an install finishes, OK resets the phase and jumps there.
 */
@Composable
fun InstallPanel(
    installer: Installer,
    context: android.content.Context,
    onInstalled: () -> Unit,
) {
    var catalog by remember { mutableStateOf<List<CatalogImage>?>(null) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<CatalogImage?>(null) }
    var user by remember { mutableStateOf("owner") }
    var password by remember { mutableStateOf("") }
    var hostname by remember { mutableStateOf("crate") }
    var memoryGB by remember { mutableStateOf(4) }
    var diskGB by remember { mutableStateOf(12) }

    LaunchedEffect(Unit) {
        Thread({
            try {
                catalog = fetchCatalog()
            } catch (e: Exception) {
                catalogError = e.message
            }
        }, "catalog-fetch").start()
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()),
    ) {

        SectionCard("Catalog") {
            catalogError?.let { Text("catalog: $it", fontSize = 14.sp) }
            val list = catalog
            if (list == null && catalogError == null) {
                Text("loading catalog…", fontSize = 14.sp)
            }
            list?.let {
                for (img in it) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${img.name} ${img.version} ${img.desktop}",
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TipIconButton(
                            icon = if (selected?.id == img.id) Icons.Filled.RadioButtonChecked
                                else Icons.Filled.RadioButtonUnchecked,
                            tip = "Select ${img.name} ${img.version}",
                            onClick = { selected = img },
                            enabled = !installer.busy,
                        )
                    }
                }
            }
        }

        selected?.let { img ->
            SectionCard("New machine") {
                Text("Install ${img.id} (${img.downloadBytes shr 20} MiB download)", fontSize = 14.sp)
                Text("Memory", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (gb in listOf(2, 4, 6, 8)) {
                        Row {
                            TipIconButton(
                                icon = if (gb == memoryGB) Icons.Filled.RadioButtonChecked
                                    else Icons.Filled.RadioButtonUnchecked,
                                tip = "$gb GB RAM",
                                onClick = { memoryGB = gb },
                                enabled = !installer.busy,
                            )
                            Text("${gb}G", fontSize = 12.sp)
                        }
                    }
                }
                Text("Disk (sparse, grows as the guest writes)", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (gb in listOf(12, 16, 24, 32, 48, 64)) {
                        Row {
                            TipIconButton(
                                icon = if (gb == diskGB) Icons.Filled.RadioButtonChecked
                                    else Icons.Filled.RadioButtonUnchecked,
                                tip = "$gb GB disk",
                                onClick = { diskGB = gb },
                                enabled = !installer.busy,
                            )
                            Text("${gb}G", fontSize = 12.sp)
                        }
                    }
                }
                TextField(value = user, onValueChange = { user = it },
                    label = { Text("user") }, singleLine = true)
                TextField(value = password, onValueChange = { password = it },
                    label = { Text("password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                TextField(value = hostname, onValueChange = { hostname = it },
                    label = { Text("hostname") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TipIconButton(
                        icon = Icons.Filled.Download,
                        tip = "Install",
                        onClick = {
                            installer.install(img, memoryGB, diskGB, user, password, hostname)
                        },
                        enabled = !installer.busy && user.isNotBlank() && password.isNotEmpty(),
                        filled = true,
                    )
                    TipIconButton(
                        icon = Icons.Filled.Close,
                        tip = "Cancel",
                        onClick = { installer.cancel() },
                        enabled = installer.busy,
                    )
                }
            }
        }

        val phase = installer.phase
        if (phase !is Installer.Phase.Idle) {
            SectionCard("Progress") {
                when (val p = phase) {
                    is Installer.Phase.Downloading -> {
                        Text("downloading ${p.done shr 20}/${p.total shr 20} MiB", fontSize = 14.sp)
                        LinearProgressIndicator(
                            progress = { (p.done.toFloat() / p.total.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is Installer.Phase.Unpacking -> {
                        Text("unpacking ${p.done shr 20}/${p.total shr 20} MiB", fontSize = 14.sp)
                        LinearProgressIndicator(
                            progress = { (p.done.toFloat() / p.total.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is Installer.Phase.Finishing -> Text("verifying + seeding…", fontSize = 14.sp)
                    is Installer.Phase.Done -> {
                        Text("installed ${p.machine.name} — tap OK to open it", fontSize = 14.sp)
                        TipIconButton(
                            icon = Icons.Filled.Check,
                            tip = "OK",
                            onClick = {
                                installer.reset()
                                onInstalled()
                            },
                        )
                    }
                    is Installer.Phase.Failed -> {
                        Text("install failed: ${p.message}", fontSize = 14.sp)
                        TipIconButton(
                            icon = Icons.Filled.Check,
                            tip = "OK",
                            onClick = { installer.reset() },
                        )
                    }
                    is Installer.Phase.Idle -> {}
                }
            }
        }
    }
}
