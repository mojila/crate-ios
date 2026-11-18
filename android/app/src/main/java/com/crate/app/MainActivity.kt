package com.crate.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crate.engine.Engine
import com.crate.engine.EngineException
import com.crate.engine.LinuxKeys
import com.crate.engine.NativeLibs
import com.crate.install.InstallPanel
import com.crate.install.Installer
import java.io.File

/** A fully-installed VM's boot files: Start boots this instead of the serial guest. */
data class BootSpec(
    val kernel: String,
    val initrd: String,
    val cmdline: String,
    val disk: String,
    val seed: String,
    val cpus: Int,
    val memoryMiB: Long,
)

/**
 * Phase-2 bring-up UI: boots the Debian guest on the serial console and
 * proves the JNI machine-control surface on device. Guest files live in the
 * app's external files dir (pushed over adb, see android/README.md):
 *   crate/vmlinuz, crate/initrd.img
 *
 * The Install screen installs catalog images into internal storage
 * (filesDir/VMs/<id>/); Boot hands their files back here as a [BootSpec].
 */
class MainActivity : ComponentActivity() {
    private val engine = Engine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val guestDir = File(getExternalFilesDir(null), "crate")
        setContent {
            MaterialTheme {
                App(engine, guestDir)
            }
        }
    }

    override fun onDestroy() {
        engine.destroy()
        super.onDestroy()
    }
}

@Composable
private fun App(engine: Engine, guestDir: File) {
    val context = LocalContext.current
    var libsReady by remember { mutableStateOf(false) }
    var engineVer by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }
    var showInstall by remember { mutableStateOf(false) }
    var pendingBoot by remember { mutableStateOf<BootSpec?>(null) }
    val installer = remember { Installer(context.applicationContext) }

    // Load natives once, off the main thread.
    LaunchedEffect(Unit) {
        Thread({
            try {
                val appContext = context.applicationContext
                NativeLibs.ensureLoaded(appContext)
                val v = engine.apiVersion()
                engineVer = "engine 0x${v.toUInt().toString(16)}"
                libsReady = true
            } catch (e: Exception) {
                loadError = e.message
            }
        }, "native-load").start()
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showInstall = false }, enabled = showInstall) {
                Text("Console")
            }
            Button(onClick = { showInstall = true }, enabled = !showInstall) {
                Text("Install")
            }
        }
        if (showInstall) {
            InstallPanel(installer, context.applicationContext, onBoot = {
                pendingBoot = it
                showInstall = false
            })
        } else {
            ConsoleScreen(engine, guestDir, pendingBoot, libsReady, engineVer, loadError)
        }
    }
}

@Composable
private fun ConsoleScreen(
    engine: Engine,
    guestDir: File,
    bootSpec: BootSpec?,
    libsReady: Boolean,
    engineVer: String,
    loadError: String?,
) {
    var bootError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var withDisplay by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val focus = LocalFocusManager.current

    LaunchedEffect(engine.consoleText) {
        scroll.scrollTo(scroll.maxValue)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("state: ${engine.stateText} $engineVer", fontSize = 14.sp)
        loadError?.let { Text("native load: $it", fontSize = 14.sp) }
        bootError?.let { Text("boot: $it", fontSize = 14.sp) }

        // Guest screen, once the GPU produces frames (needs Display
        // checked at boot + a graphical guest; the /bin/sh guest has none).
        // The panel shows while the machine lives so touch/keys are usable
        // even before the first frame. Installed VMs always boot with display.
        if ((withDisplay || bootSpec != null) && engine.isLive) {
            DisplayPanel(engine)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Checkbox(
                checked = withDisplay,
                onCheckedChange = { withDisplay = it },
                enabled = !engine.isLive,
            )
            Text("Display (GPU) at boot", fontSize = 14.sp)
        }

        Text(
            text = engine.consoleText.ifEmpty { "(console idle)" },
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("serial input") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    engine.writeText(input + "\n")
                    input = ""
                },
                enabled = libsReady && engine.isLive,
            ) { Text("Send") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    busy = true
                    bootError = null
                    Thread({
                        try {
                            if (engine.isLive) engine.destroy()
                            val spec = bootSpec
                            if (spec != null) {
                                // Installed VM: desktop boot off its own disk.
                                engine.create(
                                    kernel = spec.kernel,
                                    initrd = spec.initrd,
                                    cmdline = spec.cmdline,
                                    cpus = spec.cpus,
                                    memoryMiB = spec.memoryMiB,
                                    withDisplay = true,
                                    disk = spec.disk,
                                    seed = spec.seed,
                                )
                            } else {
                                val kernel = File(guestDir, "vmlinuz")
                                val initrd = File(guestDir, "initrd.img")
                                require(kernel.exists()) { "missing ${kernel.absolutePath}" }
                                require(initrd.exists()) { "missing ${initrd.absolutePath}" }
                                // Same guest shape as Scripts/android-boot-test.sh:
                                // straight into /bin/sh, no root device needed.
                                engine.create(
                                    kernel = kernel.absolutePath,
                                    initrd = initrd.absolutePath,
                                    cmdline = "console=ttyAMA0 rdinit=/bin/sh",
                                    cpus = 4,
                                    memoryMiB = 1024,
                                    withDisplay = withDisplay,
                                )
                            }
                            engine.start()
                            // Frames flow only while the display is active
                            // (mirrors iOS DisplayBridge.attach).
                            if (withDisplay || spec != null) engine.setDisplayActive(true)
                        } catch (e: EngineException) {
                            bootError = e.message
                        } catch (e: Exception) {
                            bootError = e.message
                        } finally {
                            busy = false
                        }
                    }, "vm-boot").start()
                },
                enabled = libsReady && !busy && (!engine.isLive || engine.stateText.startsWith("stopped")),
            ) { Text("Start") }
            Button(
                onClick = { engine.write(byteArrayOf(0x03)) },
                enabled = libsReady && engine.isLive,
            ) { Text("^C") }
            Button(
                onClick = {
                    Thread({
                        try {
                            engine.kill()
                        } finally {
                            // Let the state callback land, then free the
                            // machine so Start can boot again.
                            try {
                                Thread.sleep(500)
                            } catch (_: InterruptedException) {
                            }
                            engine.destroy()
                        }
                    }, "vm-kill").start()
                },
                enabled = libsReady && engine.isLive,
            ) { Text("Kill") }
        }
    }
}

/**
 * Maps a touch on the display view into guest tablet coordinates.
 * Returns true (event consumed). View is Fit-scaled, so the touch maps
 * linearly into guest pixels.
 */
private fun guestTouch(
    engine: Engine,
    viewSize: IntSize,
    guestW: Int,
    guestH: Int,
    event: android.view.MotionEvent,
): Boolean {
    val w = viewSize.width
    val h = viewSize.height
    if (w <= 0 || h <= 0) return false
    val x = ((event.x / w) * guestW).toInt().coerceIn(0, (guestW - 1).coerceAtLeast(0))
    val y = ((event.y / h) * guestH).toInt().coerceIn(0, (guestH - 1).coerceAtLeast(0))
    when (event.action) {
        android.view.MotionEvent.ACTION_DOWN,
        android.view.MotionEvent.ACTION_MOVE,
        -> engine.sendPointer(x, y, 1)
        android.view.MotionEvent.ACTION_UP,
        android.view.MotionEvent.ACTION_CANCEL,
        -> engine.sendPointer(x, y, 0)
    }
    return true
}

/** Sends a key down+up stroke on a background thread. */
private fun Engine.tapKey(code: Int) {    Thread({
        try {
            sendKey(code, true)
            Thread.sleep(30)
        } catch (_: InterruptedException) {
        } finally {
            sendKey(code, false)
        }
    }, "vm-key").start()
}

/**
 * Phase-3 display panel: the guest screen scaled to fit, touches as the
 * tablet (tap clicks, drag holds the button — mirrors iOS DisplayView), a
 * key row for what the soft keyboard can't type, and hardware/BT keyboards
 * via [LinuxKeys].
 */
@Composable
private fun DisplayPanel(engine: Engine) {
    val img = engine.frameImage
    var viewSize by remember { androidx.compose.runtime.mutableStateOf(IntSize.Zero) }
    val focusRequester = remember { FocusRequester() }

    if (img != null) {
    Image(
        bitmap = img,
        contentDescription = "guest display",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(engine.frameWidth.toFloat() / engine.frameHeight.coerceAtLeast(1).toFloat())
            .onSizeChanged { viewSize = it }
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                // BT/hardware keyboards: Linux code on press, release on up.
                if (event.type != KeyEventType.KeyUp && event.type != KeyEventType.KeyDown) {
                    return@onKeyEvent false
                }
                val code = LinuxKeys.codeForKeyCode(event.nativeKeyEvent.keyCode)
                    ?: return@onKeyEvent false
                engine.sendKey(code, event.type == KeyEventType.KeyDown)
                true
            }
            .pointerInteropFilter { event ->
                focusRequester.requestFocus()
                guestTouch(engine, viewSize, engine.frameWidth, engine.frameHeight, event)
            },
    )
    } else {
        Text(
            "(waiting for first frame — touch still reaches the guest tablet)",
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { viewSize = it }
                .pointerInteropFilter { event ->
                    // No frame yet: fall back to the default 1024x768 mode.
                    guestTouch(engine, viewSize, 1024, 768, event)
                },
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, code) in listOf(
            "Esc" to LinuxKeys.ESC,
            "Tab" to LinuxKeys.TAB,
            "↑" to LinuxKeys.UP,
            "↓" to LinuxKeys.DOWN,
            "←" to LinuxKeys.LEFT,
            "→" to LinuxKeys.RIGHT,
        )) {
            Button(
                onClick = { engine.tapKey(code) },
                enabled = engine.isLive,
            ) { Text(label) }
        }
    }
    Text("frames: ${engine.framesSeen}", fontSize = 12.sp)
}
