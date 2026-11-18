package com.crate.app

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import com.crate.app.ui.TipIconButton
import com.crate.app.ui.theme.CrateTheme
import com.crate.app.ui.theme.ThemeMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.platform.LocalViewConfiguration
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

/** Top menu. Home (the console) is selected by default. */
private enum class Tab { Home, Install, Settings }

/** A fully-installed VM's boot files: Start boots this instead of the serial guest. */
data class BootSpec(    val kernel: String,
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
            var mode by remember { mutableStateOf(ThemeMode.load(this)) }
            CrateTheme(mode) {
                App(engine, guestDir, themeMode = mode, onThemeMode = {
                    mode = it
                    ThemeMode.save(this, it)
                })
            }
        }
    }

    override fun onDestroy() {
        engine.destroy()
        super.onDestroy()
    }
}

@Composable
private fun App(
    engine: Engine,
    guestDir: File,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
) {
    val context = LocalContext.current
    var libsReady by remember { mutableStateOf(false) }
    var engineVer by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(Tab.Home) }
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

    // While a machine exists, pin landscape + hide the status bar for a
    // full-screen guest; restore on kill (stateText returns to "idle" in
    // destroy()). Keyed on stateText — isLive is a plain getter, not state.
    val booted = engine.stateText != "idle"
    LaunchedEffect(booted) {
        val activity = context as? Activity ?: return@LaunchedEffect
        if (booted) {
            activity.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            activity.window.insetsController?.let { c ->
                c.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.statusBars())
            }
        } else {
            activity.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity.window.insetsController?.show(WindowInsets.Type.statusBars())
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TipIconButton(
                icon = Icons.Filled.Home,
                tip = "Home",
                onClick = { tab = Tab.Home },
                filled = tab == Tab.Home,
            )
            TipIconButton(
                icon = Icons.Filled.Download,
                tip = "Install",
                onClick = { tab = Tab.Install },
                filled = tab == Tab.Install,
            )
            TipIconButton(
                icon = Icons.Filled.Settings,
                tip = "Settings",
                onClick = { tab = Tab.Settings },
                filled = tab == Tab.Settings,
            )
        }
        when (tab) {
            Tab.Install -> InstallPanel(installer, context.applicationContext, onBoot = {
                pendingBoot = it
                tab = Tab.Home
            })
            Tab.Settings -> SettingsScreen(
                themeMode = themeMode,
                onThemeMode = onThemeMode,
                engineVer = engineVer,
            )
            Tab.Home -> ConsoleScreen(engine, guestDir, pendingBoot, libsReady, engineVer, loadError)
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
            TipIconButton(
                icon = Icons.Filled.Send,
                tip = "Send",
                onClick = {
                    engine.writeText(input + "\n")
                    input = ""
                },
                enabled = libsReady && engine.isLive,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TipIconButton(
                icon = Icons.Filled.PlayArrow,
                tip = "Start",
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
            )
            TipIconButton(
                icon = Icons.Filled.Keyboard,
                tip = "Send Ctrl+C",
                onClick = { engine.write(byteArrayOf(0x03)) },
                enabled = libsReady && engine.isLive,
            )
            TipIconButton(
                icon = Icons.Filled.Stop,
                tip = "Kill",
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
            )
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

/**
 * Touchpad mode: the engine only takes an absolute tablet position
 * (`crate_input_pointer` is positions, not movements), so the app keeps a
 * virtual cursor in guest pixels and turns finger deltas into moves:
 * 1-finger drag moves the cursor (hover, no button), quick tap left-clicks,
 * long-press then drag holds the left button (window dragging), and a
 * 2-finger tap right-clicks. The guest draws its own cursor, so no overlay
 * is needed. Returns true (event consumed).
 */
private class PadState {
    var x = 512f
    var y = 384f
    var gw = 0
    var gh = 0
    var downX = 0f
    var downY = 0f
    var lastX = 0f
    var lastY = 0f
    var downT = 0L
    var moved = false
    var multi = false
    var held = false
}

/** Touchpad gain: one full-width swipe crosses ~2 guest screens. */
private const val PAD_GAIN = 2f

private fun padEvent(
    engine: Engine,
    pad: PadState,
    viewSize: IntSize,
    guestW: Int,
    guestH: Int,
    slop: Float,
    longPressMs: Long,
    event: android.view.MotionEvent,
): Boolean {
    // Recenter when the guest mode changes (boot, resolution switch).
    if (pad.gw != guestW || pad.gh != guestH) {
        pad.gw = guestW
        pad.gh = guestH
        pad.x = guestW / 2f
        pad.y = guestH / 2f
    }
    val w = viewSize.width
    val h = viewSize.height
    if (w <= 0 || h <= 0) return true
    val maxX = (guestW - 1).coerceAtLeast(0)
    val maxY = (guestH - 1).coerceAtLeast(0)
    val sx = guestW / w.toFloat() * PAD_GAIN
    val sy = guestH / h.toFloat() * PAD_GAIN
    fun send() {
        engine.sendPointer(
            pad.x.toInt().coerceIn(0, maxX),
            pad.y.toInt().coerceIn(0, maxY),
            if (pad.held) 1 else 0,
        )
    }
    when (event.actionMasked) {
        android.view.MotionEvent.ACTION_DOWN -> {
            pad.downX = event.getX(0)
            pad.downY = event.getY(0)
            pad.lastX = pad.downX
            pad.lastY = pad.downY
            pad.downT = event.eventTime
            pad.moved = false
            pad.multi = false
            pad.held = false
        }
        android.view.MotionEvent.ACTION_POINTER_DOWN -> pad.multi = true
        android.view.MotionEvent.ACTION_MOVE -> {
            val x = event.getX(0)
            val y = event.getY(0)
            if (kotlin.math.abs(x - pad.downX) > slop ||
                kotlin.math.abs(y - pad.downY) > slop
            ) {
                pad.moved = true
            }
            if (!pad.multi) {
                if (!pad.held && !pad.moved && event.eventTime - pad.downT > longPressMs) {
                    pad.held = true
                    send()
                }
                pad.x = (pad.x + (x - pad.lastX) * sx).coerceIn(0f, maxX.toFloat())
                pad.y = (pad.y + (y - pad.lastY) * sy).coerceIn(0f, maxY.toFloat())
                send()
            }
            pad.lastX = x
            pad.lastY = y
        }
        android.view.MotionEvent.ACTION_UP -> {
            if (pad.multi && !pad.moved) {
                engine.sendPointer(pad.x.toInt().coerceIn(0, maxX), pad.y.toInt().coerceIn(0, maxY), 2)
                engine.sendPointer(pad.x.toInt().coerceIn(0, maxX), pad.y.toInt().coerceIn(0, maxY), 0)
            } else if (pad.held) {
                pad.held = false
                send()
            } else if (!pad.moved) {
                engine.sendPointer(pad.x.toInt().coerceIn(0, maxX), pad.y.toInt().coerceIn(0, maxY), 1)
                engine.sendPointer(pad.x.toInt().coerceIn(0, maxX), pad.y.toInt().coerceIn(0, maxY), 0)
            }
            pad.held = false
        }
        android.view.MotionEvent.ACTION_CANCEL -> {
            if (pad.held) {
                pad.held = false
                send()
            }
        }
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
    // Touchpad (relative) is the default; direct tablet stays one tap away.
    var touchpad by remember { mutableStateOf(true) }
    val pad = remember { PadState() }
    val viewConfig = LocalViewConfiguration.current
    val slop = viewConfig.touchSlop
    val longPressMs = viewConfig.longPressTimeoutMillis
    fun handleTouch(viewW: IntSize, gw: Int, gh: Int, event: android.view.MotionEvent): Boolean {
        focusRequester.requestFocus()
        return if (touchpad) {
            padEvent(engine, pad, viewW, gw, gh, slop, longPressMs, event)
        } else {
            guestTouch(engine, viewW, gw, gh, event)
        }
    }

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
                handleTouch(viewSize, engine.frameWidth, engine.frameHeight, event)
            },
    )
    } else {
        Text(
            if (touchpad) "(waiting for first frame — touchpad drives the guest cursor)"
            else "(waiting for first frame — touch still reaches the guest tablet)",
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { viewSize = it }
                .pointerInteropFilter { event ->
                    // No frame yet: fall back to the default 1024x768 mode.
                    handleTouch(viewSize, 1024, 768, event)
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TipIconButton(
            icon = if (touchpad) Icons.Filled.Mouse else Icons.Filled.TouchApp,
            tip = if (touchpad) "Touchpad on — tap for direct touch"
                else "Direct touch — tap for touchpad",
            onClick = { touchpad = !touchpad },
            filled = touchpad,
        )
        Text("frames: ${engine.framesSeen}", fontSize = 12.sp)
    }
}
