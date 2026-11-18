package com.crate.install

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import com.crate.app.BootSpec
import java.io.File

/**
 * Install phase UI: lists installed VMs (Boot) and the catalog (Install).
 * The download runs while the app is open; leaving the screen keeps it
 * going, cancelling or failure removes the VM's files.
 */
@Composable
fun InstallPanel(
    installer: Installer,
    context: android.content.Context,
    onBoot: (BootSpec) -> Unit,
) {
    var catalog by remember { mutableStateOf<List<CatalogImage>?>(null) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<CatalogImage?>(null) }
    var user by remember { mutableStateOf("owner") }
    var password by remember { mutableStateOf("") }
    var hostname by remember { mutableStateOf("crate") }
    var machines by remember { mutableStateOf(Installer.machines(context)) }

    LaunchedEffect(Unit) {
        Thread({
            try {
                catalog = fetchCatalog()
            } catch (e: Exception) {
                catalogError = e.message
            }
        }, "catalog-fetch").start()
    }

    // Refresh the machine list after an install finishes.
    LaunchedEffect(installer.phase) {
        if (installer.phase is Installer.Phase.Done) {
            machines = Installer.machines(context)
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()),
    ) {
        Text("Installed VMs", fontSize = 16.sp)
        if (machines.isEmpty()) {
            Text("none yet", fontSize = 14.sp)
        }
        for (m in machines) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "${m.name} ${m.version} ${m.desktop} (${m.memoryGB}G/${m.diskGB}G)",
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = {
                    val dir = File(Installer.vmsRoot(context), m.id)
                    onBoot(
                        BootSpec(
                            kernel = File(dir, "vmlinuz").absolutePath,
                            initrd = File(dir, "initrd.img").absolutePath,
                            cmdline = m.cmdline,
                            disk = File(dir, "disk.img").absolutePath,
                            seed = File(dir, "seed.img").absolutePath,
                            cpus = m.cpus,
                            memoryMiB = m.memoryGB.toLong() * 1024,
                        ),
                    )
                }) { Text("Boot") }
            }
        }

        Text("Install from catalog", fontSize = 16.sp)
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
                    Button(
                        onClick = { selected = img },
                        enabled = !installer.busy,
                    ) { Text(if (selected?.id == img.id) "●" else "Pick") }
                }
            }
        }

        selected?.let { img ->
            Text("Install ${img.id} (${img.downloadBytes shr 20} MiB download)", fontSize = 14.sp)
            TextField(value = user, onValueChange = { user = it },
                label = { Text("user") }, singleLine = true)
            TextField(value = password, onValueChange = { password = it },
                label = { Text("password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            TextField(value = hostname, onValueChange = { hostname = it },
                label = { Text("hostname") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        installer.install(img, 4, 12, user, password, hostname)
                    },
                    enabled = !installer.busy && user.isNotBlank() && password.isNotEmpty(),
                ) { Text("Install") }
                Button(
                    onClick = { installer.cancel() },
                    enabled = installer.busy,
                ) { Text("Cancel") }
            }
        }

        when (val p = installer.phase) {
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
                Text("installed ${p.machine.name} — tap Boot above", fontSize = 14.sp,
                    modifier = Modifier.padding(top = 4.dp))
                Button(onClick = { installer.reset() }) { Text("OK") }
            }
            is Installer.Phase.Failed -> {
                Text("install failed: ${p.message}", fontSize = 14.sp)
                Button(onClick = { installer.reset() }) { Text("OK") }
            }
            is Installer.Phase.Idle -> {}
        }
    }
}
