package com.crate.app

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import com.crate.app.ui.SectionCard
import com.crate.app.ui.TipIconButton
import com.crate.app.ui.theme.CrateTheme
import com.crate.app.ui.theme.ThemeMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
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
import com.crate.engine.GuestDns
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
    // Process-wide: see Engine.shared. Never destroy here — a second
    // activity instance (launcher tap) shares the live machine, and
    // destroying in onDestroy would kill a running guest under it.
    private val engine: Engine get() = Engine.shared

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var mode by remember { mutableStateOf(ThemeMode.load(this)) }
            CrateTheme(mode) {
                App(engine, themeMode = mode, onThemeMode = {
                    mode = it
                    ThemeMode.save(this, it)
                })
            }
        }
    }

    override fun onDestroy() {
        // No engine.destroy(): the machine outlives any one activity
        // instance; Kill frees it explicitly. Process death reclaims it.
        super.onDestroy()
    }
}

@Composable
private fun App(
    engine: Engine,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
) {
    val context = LocalContext.current
    var libsReady by remember { mutableStateOf(false) }
    var engineVer by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(Tab.Home) }
    var displayAtBoot by remember { mutableStateOf(VmPrefs.loadDisplayAtBoot(context)) }
    // Controls (status, buttons, menus) hide while a display VM runs — the
    // guest goes edge-to-edge by default, one tap brings chrome back.
    var controlsVisible by remember { mutableStateOf(true) }
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

    // While a display machine lives, pin landscape + hide the status bar
    // for a full-screen guest; restore on kill (stateText returns to "idle"
    // in destroy()). Keyed on stateText — isLive is a plain getter, not
    // state. Serial-only boots stay in portrait.
    val booted = engine.stateText != "idle" && displayAtBoot
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

    // Fullscreen guest: no menu row, no padding chrome — the display owns
    // the window, with its own floating show-controls button. Serial/idle
    // keep the normal menu + screen below.
    if (tab == Tab.Home && booted && !controlsVisible) {
        Box(
            modifier = Modifier.fillMaxSize()
                .background(Color.Black)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            HomeScreen(engine, displayAtBoot, libsReady, engineVer, loadError,
                controlsVisible, { controlsVisible = it })
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
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
            Tab.Install -> InstallPanel(installer, context.applicationContext, onInstalled = {
                tab = Tab.Home
            })
            Tab.Settings -> SettingsScreen(
                themeMode = themeMode,
                onThemeMode = onThemeMode,
                displayAtBoot = displayAtBoot,
                onDisplayAtBoot = {
                    displayAtBoot = it
                    VmPrefs.saveDisplayAtBoot(context, it)
                },
                engineVer = engineVer,
            )
            Tab.Home -> HomeScreen(engine, displayAtBoot, libsReady, engineVer, loadError,
                controlsVisible, { controlsVisible = it })
        }
    }
}

/**
 * Process-wide boot serialization. HomeScreen is rebuilt on every tab visit,
 * so its `busy` flag can't stop a stale screen from booting twice over one
 * slow create: the two machines would collide ("one machine per process")
 * and orphan one forever. Boot and kill serialize here instead.
 */
private val bootLock = Any()

@Composable
private fun HomeScreen(
    engine: Engine,
    displayAtBoot: Boolean,
    libsReady: Boolean,
    engineVer: String,
    loadError: String?,
    controlsVisible: Boolean,
    onControlsVisible: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    // Reloaded every visit: Home recomposes fresh whenever the tab is
    // selected, so a just-installed OS shows up after Install's OK jump.
    var machines by remember { mutableStateOf(Installer.machines(context.applicationContext)) }
    var bootError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var bootedName by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var showSerialInput by remember { mutableStateOf(false) }
    var showConsole by remember { mutableStateOf(false) }
    // Touchpad (relative) is the default; direct tablet stays one tap away.
    // Hoisted here so fullscreen and card modes share the same pointer mode.
    // A connected mouse forces direct mode (its position is already
    // absolute) and disables the toggle until it disconnects.
    var touchpad by remember { mutableStateOf(true) }
    val hasMouse = rememberHasPhysicalMouse()
    val scroll = rememberScrollState()
    val focus = LocalFocusManager.current
    val runningDisplay = displayAtBoot && engine.isLive

    /** Boot a VM immediately on its own thread (create is slow). */
    fun boot(name: String, spec: BootSpec) {
        busy = true
        bootError = null
        bootedName = name
        // A display boot lands in fullscreen: hide status + buttons.
        if (displayAtBoot) onControlsVisible(false)
        Thread({
            synchronized(bootLock) {
            try {
                GuestDns.ensure(context.applicationContext)
                if (engine.isLive) engine.destroy()
                engine.create(
                    kernel = spec.kernel,
                    initrd = spec.initrd,
                    cmdline = spec.cmdline,
                    cpus = spec.cpus,
                    memoryMiB = spec.memoryMiB,
                    withDisplay = displayAtBoot,
                    disk = spec.disk,
                    seed = spec.seed,
                )
                engine.start()
                // Frames flow only while the display is active
                // (mirrors iOS DisplayBridge.attach).
                if (displayAtBoot) engine.setDisplayActive(true)
            } catch (e: EngineException) {
                bootError = e.message
                bootedName = null
            } catch (e: Exception) {
                bootError = e.message
                bootedName = null
            } finally {
                busy = false
            }
            }
        }, "vm-boot").start()
    }

    /** Kill the machine, then free it so another OS can boot. */
    fun kill() {
        Thread({
            synchronized(bootLock) {
            try {
                engine.kill()
            } finally {
                // Let the state callback land, then free the machine.
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                }
                engine.destroy()
                bootedName = null
            }
            }
        }, "vm-kill").start()
        // Back to the full-chrome idle screen.
        onControlsVisible(true)
    }

    LaunchedEffect(engine.consoleText, showConsole) {
        // Only yank the scroll while the console is actually shown — otherwise
        // every kernel log line during boot would hide the display card
        // behind the status header.
        if (showConsole) scroll.scrollTo(scroll.maxValue)
    }

    // Fullscreen guest: just the display on black + a floating button to
    // bring controls back. No status, no buttons, no cards.
    if (runningDisplay && !controlsVisible) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
        ) {
            GuestImage(
                engine = engine,
                touchpad = touchpad && !hasMouse,
                mouseActive = hasMouse,
                modifier = Modifier.fillMaxSize().align(Alignment.Center),
            )
            TipIconButton(
                icon = Icons.Filled.FullscreenExit,
                tip = "Show controls",
                onClick = { onControlsVisible(true) },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
        }
        return
    }

    // Status up top, scrollable content in the middle, action bar pinned
    // to the bottom so Kill/^C stay reachable even when the guest display
    // fills the screen.
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionCard("Status") {
            val status = if (engine.isLive) {
                "Running${bootedName?.let { " — $it" } ?: ""}"
            } else {
                "Idle"
            }
            Text("$status  $engineVer", fontSize = 14.sp)
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            loadError?.let {
                Text("native load: $it", fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error)
            }
            bootError?.let {
                Text("boot: $it", fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error)
            }
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Installed operating systems. Boot starts the VM immediately;
            // one machine per process, so Boot is gated while one lives.
            // Hidden while a VM runs — nothing to start until it stops.
            if (!engine.isLive) {
                SectionCard("Machines") {
                    if (machines.isEmpty()) {
                        Text("none yet — grab one from Install", fontSize = 14.sp)
                    }
                    for (m in machines) {
                        ListItem(
                            headlineContent = { Text("${m.name} ${m.version}") },
                            supportingContent = {
                                Text("${m.desktop} • ${m.memoryGB}G RAM / ${m.diskGB}G disk")
                            },
                            trailingContent = {
                                TipIconButton(
                                    icon = Icons.Filled.PlayArrow,
                                    tip = "Boot ${m.name}",
                                    onClick = {
                                        val dir = File(
                                            Installer.vmsRoot(context.applicationContext), m.id)
                                        boot(
                                            m.name,
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
                                    },
                                    enabled = libsReady && !busy && !engine.isLive,
                                    filled = true,
                                )
                            },
                        )
                    }
                }
            }

            // Guest screen first when a display VM runs, so touch/keys are
            // usable before the first frame. Hidden for serial-only boots.
            // Carded like every other section; the panel caps its own height
            // so the key strip + action bar stay on screen with it.
            if (displayAtBoot && engine.isLive) {
                SectionCard("Display") {
                    DisplayPanel(engine, touchpad, { touchpad = it }, hasMouse)
                }
            }

            // Serial console output + input share one card, each behind its
            // action-bar toggle (both default hidden).
            if (engine.isLive && (showConsole || showSerialInput)) {
                SectionCard("Console") {
                    if (showConsole) {
                        Text(
                            text = engine.consoleText.ifEmpty { "(console idle)" },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth().verticalScroll(scroll),
                        )
                    }
                    if (showSerialInput) {
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
                    }
                }
            }
        }

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (runningDisplay) {
                TipIconButton(
                    icon = Icons.Filled.Fullscreen,
                    tip = "Hide controls — fullscreen",
                    onClick = { onControlsVisible(false) },
                )
            }
            TipIconButton(
                icon = Icons.Filled.Terminal,
                tip = if (showSerialInput) "Hide serial input" else "Show serial input",
                onClick = { showSerialInput = !showSerialInput },
                filled = showSerialInput,
            )
            TipIconButton(
                icon = Icons.Filled.Subject,
                tip = if (showConsole) "Hide console" else "Show console",
                onClick = { showConsole = !showConsole },
                filled = showConsole,
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
                onClick = { kill() },
                enabled = libsReady && engine.isLive,
            )
        }
    }
}

/**
 * Maps a touch on the display view into guest tablet coordinates.
 * Returns true (event consumed). View is Fit-scaled, so the touch maps
 * linearly into guest pixels, then up to crate's 0..32767 tablet range
 * (mirrors iOS DisplayBridge.pointer — raw guest pixels only use the
 * top-left ~3% of the tablet).
 */
private fun sendTablet(engine: Engine, gx: Int, gy: Int, guestW: Int, guestH: Int, buttons: Int) {
    val nx = (gx.toFloat() / guestW.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    val ny = (gy.toFloat() / guestH.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    engine.sendPointer((nx * 32767f).toInt(), (ny * 32767f).toInt(), buttons)
}

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
        -> sendTablet(engine, x, y, guestW, guestH, 1)
        android.view.MotionEvent.ACTION_UP,
        android.view.MotionEvent.ACTION_CANCEL,
        -> sendTablet(engine, x, y, guestW, guestH, 0)
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
    /** Bumps every gesture end; stale long-press timers no-op on mismatch. */
    var gestureId = 0
}

/** Touchpad gain: one full-width swipe crosses ~2 guest screens. */
private const val PAD_GAIN = 2f

/**
 * True while a physical mouse (Bluetooth/USB/OTG) is attached. Mice
 * register as an [InputDevice] with source MOUSE. That alone over-matches:
 * this phone's built-in `sec_touchpad` also carries MOUSE (it is a touchpad,
 * source TOUCHPAD), so touchpads, touchscreens, styli and virtual devices
 * are excluded — only a genuine external pointer counts. Live-updated so
 * pairing mid-session switches over.
 */
@Composable
private fun rememberHasPhysicalMouse(): Boolean {
    val context = LocalContext.current
    var hasMouse by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val manager =
            context.getSystemService(Context.INPUT_SERVICE) as InputManager
        fun isPhysicalMouse(device: InputDevice): Boolean {
            if (device.isVirtual) return false
            if (!device.supportsSource(InputDevice.SOURCE_MOUSE)) return false
            if (device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) return false
            if (device.supportsSource(InputDevice.SOURCE_TOUCHPAD)) return false
            if (device.supportsSource(InputDevice.SOURCE_STYLUS)) return false
            return true
        }
        fun query(): Boolean = InputDevice.getDeviceIds().any { id ->
            try {
                val device = InputDevice.getDevice(id)
                device != null && isPhysicalMouse(device)
            } catch (_: Exception) {
                false
            }
        }
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(id: Int) { hasMouse = query() }
            override fun onInputDeviceRemoved(id: Int) { hasMouse = query() }
            override fun onInputDeviceChanged(id: Int) { hasMouse = query() }
        }
        hasMouse = query()
        manager.registerInputDeviceListener(listener, null)
        onDispose { manager.unregisterInputDeviceListener(listener) }
    }
    return hasMouse
}

/** Android button bits map 1:1 onto the guest's BUTTON_LEFT/RIGHT/MIDDLE. */
private fun mouseButtons(state: Int): Int {
    var buttons = 0
    if (state and android.view.MotionEvent.BUTTON_PRIMARY != 0) buttons = buttons or 1
    if (state and android.view.MotionEvent.BUTTON_SECONDARY != 0) buttons = buttons or 2
    if (state and android.view.MotionEvent.BUTTON_TERTIARY != 0) buttons = buttons or 4
    return buttons
}

private fun isMouseEvent(event: android.view.MotionEvent): Boolean {
    try {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return true
    } catch (_: Exception) {
    }
    return try {
        event.getToolType(0) == android.view.MotionEvent.TOOL_TYPE_MOUSE
    } catch (_: Exception) {
        false
    }
}

/**
 * Physical-mouse path: the mouse already reports an absolute position, so
 * it drives the guest tablet directly — no touchpad accumulation. Hover
 * moves the cursor (buttons 0), presses carry the real button bits, and
 * the wheel scrolls. Returns true (event consumed).
 */
private fun mouseTouch(
    engine: Engine,
    viewSize: IntSize,
    guestW: Int,
    guestH: Int,
    event: android.view.MotionEvent,
): Boolean {
    val w = viewSize.width
    val h = viewSize.height
    if (w <= 0 || h <= 0) return true
    if (event.actionMasked == android.view.MotionEvent.ACTION_SCROLL) {
        val dx = event.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL).toInt()
        val dy = event.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL).toInt()
        if (dx != 0 || dy != 0) engine.sendScroll(dx, dy)
        return true
    }
    val x = ((event.x / w) * guestW).toInt().coerceIn(0, (guestW - 1).coerceAtLeast(0))
    val y = ((event.y / h) * guestH).toInt().coerceIn(0, (guestH - 1).coerceAtLeast(0))
    when (event.actionMasked) {
        android.view.MotionEvent.ACTION_HOVER_ENTER,
        android.view.MotionEvent.ACTION_HOVER_MOVE,
        android.view.MotionEvent.ACTION_HOVER_EXIT,
        android.view.MotionEvent.ACTION_DOWN,
        android.view.MotionEvent.ACTION_MOVE,
        android.view.MotionEvent.ACTION_UP,
        android.view.MotionEvent.ACTION_CANCEL,
        android.view.MotionEvent.ACTION_BUTTON_PRESS,
        android.view.MotionEvent.ACTION_BUTTON_RELEASE,
        android.view.MotionEvent.ACTION_POINTER_DOWN,
        android.view.MotionEvent.ACTION_POINTER_UP,
        -> sendTablet(engine, x, y, guestW, guestH, mouseButtons(event.buttonState))
    }
    return true
}

private fun padEvent(
    engine: Engine,
    pad: PadState,
    viewSize: IntSize,
    guestW: Int,
    guestH: Int,
    slop: Float,
    longPressMs: Long,
    onDown: () -> Unit,
    onEnd: () -> Unit,
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
        sendTablet(
            engine,
            pad.x.toInt().coerceIn(0, maxX),
            pad.y.toInt().coerceIn(0, maxY),
            guestW, guestH,
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
            onDown()
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
            onEnd()
            if (pad.multi && !pad.moved) {
                sendTablet(engine, pad.x.toInt().coerceIn(0, maxX), pad.y.toInt().coerceIn(0, maxY), guestW, guestH, 2)
                sendTablet(engine, pad.x.toInt().coerceIn(0, maxX), pad.y.toInt().coerceIn(0, maxY), guestW, guestH, 0)
            } else if (pad.held) {
                pad.held = false
                send()
            } else if (!pad.moved) {
                sendTablet(engine, pad.x.toInt().coerceIn(0, maxX), pad.y.toInt().coerceIn(0, maxY), guestW, guestH, 1)
                sendTablet(engine, pad.x.toInt().coerceIn(0, maxX), pad.y.toInt().coerceIn(0, maxY), guestW, guestH, 0)
            }
            pad.held = false
        }
        android.view.MotionEvent.ACTION_CANCEL -> {
            onEnd()
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
 * The touchable guest screen alone — no keys, no toggles. Both the card
 * and fullscreen modes share it; chrome lives in [DisplayPanel].
 */
@Composable
private fun GuestImage(
    engine: Engine,
    touchpad: Boolean,
    modifier: Modifier = Modifier,
    mouseActive: Boolean = false,
) {
    val img = engine.frameImage
    var viewSize by remember { androidx.compose.runtime.mutableStateOf(IntSize.Zero) }
    val focusRequester = remember { FocusRequester() }
    // A paired keyboard needs somewhere to send keys before the first
    // tap: grab focus once the first frame lands. Keyed on the null
    // transition so later frames never yank focus from the serial field.
    var autofocused by remember { mutableStateOf(false) }
    LaunchedEffect(engine.isLive) {
        // A fresh boot gets a fresh grab; otherwise focus would sit on
        // whatever was last tapped (e.g. Kill) across a reboot.
        if (!engine.isLive) autofocused = false
    }
    LaunchedEffect(img != null) {
        if (img != null && !autofocused) {
            autofocused = true
            try {
                focusRequester.requestFocus()
            } catch (_: Exception) {
            }
        }
    }
    val pad = remember { PadState() }
    val viewConfig = LocalViewConfiguration.current
    val slop = viewConfig.touchSlop
    val longPressMs = viewConfig.longPressTimeoutMillis
    // Long-press arm: a still finger sends no MOVE events, so the hold
    // can't be detected in the event stream alone — a timer fires it.
    val holdTimer = remember { Handler(Looper.getMainLooper()) }
    fun handleTouch(viewW: IntSize, gw: Int, gh: Int, event: android.view.MotionEvent): Boolean {
        focusRequester.requestFocus()
        // A connected mouse drives the tablet directly even with the
        // touchpad toggle on — its position is already absolute.
        if (mouseActive || isMouseEvent(event)) {
            return mouseTouch(engine, viewW, gw, gh, event)
        }
        return if (touchpad) {
            padEvent(engine, pad, viewW, gw, gh, slop, longPressMs,
                onDown = {
                    pad.gestureId++
                    val id = pad.gestureId
                    holdTimer.postDelayed({
                        if (pad.gestureId == id && !pad.moved && !pad.multi && !pad.held) {
                            pad.held = true
                            val maxX = (pad.gw - 1).coerceAtLeast(0)
                            val maxY = (pad.gh - 1).coerceAtLeast(0)
                            sendTablet(
                                engine,
                                pad.x.toInt().coerceIn(0, maxX),
                                pad.y.toInt().coerceIn(0, maxY),
                                pad.gw.coerceAtLeast(1), pad.gh.coerceAtLeast(1), 1,
                            )
                        }
                    }, longPressMs)
                },
                onEnd = { pad.gestureId++ },
                event = event,
            )
        } else {
            guestTouch(engine, viewW, gw, gh, event)
        }
    }

    if (img != null) {
    Image(
        bitmap = img,
        contentDescription = "guest display",
        contentScale = ContentScale.Fit,
        modifier = modifier
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
            if (mouseActive) "(waiting for first frame — mouse drives the guest cursor)"
            else if (touchpad) "(waiting for first frame — touchpad drives the guest cursor)"
            else "(waiting for first frame — touch still reaches the guest tablet)",
            fontSize = 12.sp,
            modifier = modifier
                .onSizeChanged { viewSize = it }
                .pointerInteropFilter { event ->
                    // No frame yet: fall back to the default 1024x768 mode.
                    handleTouch(viewSize, 1024, 768, event)
                },
        )
    }
}

/**
 * Phase-3 display panel: the guest screen scaled to fit, touches as the
 * tablet (tap clicks, drag holds the button — mirrors iOS DisplayView), a
 * key row for what the soft keyboard can't type, and hardware/BT keyboards
 * via [LinuxKeys].
 */
@Composable
private fun DisplayPanel(
    engine: Engine,
    touchpad: Boolean,
    onTouchpad: (Boolean) -> Unit,
    mouseActive: Boolean = false,
) {
    // Explicit size from the available width: aspectRatio never resolved a
    // height inside the scrolling column (measured 2052x9 on device), so
    // derive height = width / aspect here and hand GuestImage a fixed box.
    val aspect = if (engine.frameWidth > 0 && engine.frameHeight > 0) {
        engine.frameWidth.toFloat() / engine.frameHeight.toFloat()
    } else {
        16f / 10f
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val h = (maxWidth / aspect).coerceAtMost(520.dp).coerceAtLeast(120.dp)
        GuestImage(
            engine = engine,
            touchpad = touchpad && !mouseActive,
            mouseActive = mouseActive,
            modifier = Modifier.fillMaxWidth().height(h),
        )
    }

    // Compact key strip: one horizontally-scrollable row of small buttons
    // so it never stacks into a tall block that pushes the display away.
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
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
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) { Text(label, fontSize = 12.sp) }
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TipIconButton(
            icon = if (touchpad && !mouseActive) Icons.Filled.Mouse else Icons.Filled.TouchApp,
            tip = if (mouseActive) "Physical mouse connected — touchpad off"
                else if (touchpad) "Touchpad on — tap for direct touch"
                else "Direct touch — tap for touchpad",
            onClick = { onTouchpad(!touchpad) },
            enabled = !mouseActive,
            filled = touchpad && !mouseActive,
        )
        Text("frames: ${engine.framesSeen}", fontSize = 12.sp)
    }
}
