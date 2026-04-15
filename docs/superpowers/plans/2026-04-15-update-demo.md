# Update Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `runUpdateDemo` Gradle task that opens a Compose Desktop window showing the real production update UI (UpdateBadge, UpdateDialog, UpdateDiagnosticsDialog) driven by a hand-owned MutableStateFlow, with a control panel that exposes every state + a scripted auto-play sequence. Rename the existing `runDemo` to `runSipDemo` so the two demos coexist.

**Architecture:** Standalone Compose Desktop `main()` in the test source set (no production code impact, same pattern as existing `DemoMain.kt`). A `UpdateDemoDriver` owns `MutableStateFlow<UpdateState>` + `MutableStateFlow<CallState>` and exposes read-only StateFlows consumed by the production composables. Buttons in a control-panel composable call driver methods that set flow values. An auto-play coroutine scripts a deterministic walk through every state.

**Tech Stack:** Kotlin 2.1.20, Compose Multiplatform 1.8.2, kotlinx.coroutines, Material3. Reuses `feature/main/update/UpdateBadge`, `UpdateDialog`, `UpdateDiagnosticsDialog`, `YallaSipPhoneTheme`, `UpdateState`, `UpdateRelease`, `UpdateInstaller`, `UpdateChannel`, `CallState`.

---

## File Structure

**New (all in `src/test/kotlin/uz/yalla/sipphone/demo/update/`):**
- `UpdateDemoDriver.kt` — owns mutable flows, exposes read-only ones, scenario methods
- `UpdateDemoConsoleLogger.kt` — timestamped stdout trace of state transitions
- `UpdateDemoAutoPlay.kt` — scripted coroutine that walks the state catalog
- `UpdateDemoControlPanel.kt` — @Composable button grid + live state inspector
- `UpdateDemoMain.kt` — `fun main()`, Compose `application { Window { ... } }`, wires everything

**Modified:**
- `build.gradle.kts` — rename `runDemo` → `runSipDemo`, add `runUpdateDemo`
- `.claude/CLAUDE.md` — update Quick Commands block for the rename + new task

**Unchanged (reused from production):** `UpdateBadge`, `UpdateDialog`, `UpdateDiagnosticsDialog`, `YallaSipPhoneTheme`, `StringResources`/`UzStrings`/`RuStrings`, `UpdateState`, `UpdateRelease`, `UpdateInstaller`, `UpdateChannel`, `CallState`.

---

### Task 1: UpdateDemoDriver

**Files:**
- Create: `src/test/kotlin/uz/yalla/sipphone/demo/update/UpdateDemoDriver.kt`

- [ ] **Step 1: Write the driver file**

```kotlin
package uz.yalla.sipphone.demo.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.update.UpdateChannel
import uz.yalla.sipphone.domain.update.UpdateInstaller
import uz.yalla.sipphone.domain.update.UpdateRelease
import uz.yalla.sipphone.domain.update.UpdateState

/**
 * Demo-only driver for the update UI. Owns MutableStateFlows that feed
 * the real production composables. Scenario methods flip states directly
 * — no UpdateManager, no network, no installer process.
 */
class UpdateDemoDriver(private val scope: CoroutineScope) {

    val currentVersion: String = "1.0.4"

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _channel = MutableStateFlow(UpdateChannel.STABLE)
    val channel: StateFlow<UpdateChannel> = _channel.asStateFlow()

    private val _locale = MutableStateFlow("uz")
    val locale: StateFlow<String> = _locale.asStateFlow()

    private val _diagnosticsVisible = MutableStateFlow(false)
    val diagnosticsVisible: StateFlow<Boolean> = _diagnosticsVisible.asStateFlow()

    private val sampleRelease = UpdateRelease(
        version = "1.0.5",
        minSupportedVersion = "1.0.0",
        releaseNotes = """
            • Fixed SIP registration retry on weak networks
            • Hidden beta channel toggle via Ctrl+Shift+Alt+B
            • Improved call-active guard for updates
            • Better error messages in the update diagnostics panel
        """.trimIndent(),
        installer = UpdateInstaller(
            url = "http://192.168.0.98:8080/releases/YallaSipPhone-1.0.5.msi",
            sha256 = "a".repeat(64),
            size = 34_500_000L,
        ),
    )

    fun reset() { _state.value = UpdateState.Idle }

    fun showChecking() { _state.value = UpdateState.Checking }

    fun showDownloading(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        val size = sampleRelease.installer.size
        val read = size * clamped / 100L
        _state.value = UpdateState.Downloading(sampleRelease, read, size)
    }

    fun showVerifying() { _state.value = UpdateState.Verifying(sampleRelease) }

    fun showReady() { _state.value = UpdateState.ReadyToInstall(sampleRelease, "/tmp/fake.msi") }

    fun showInstalling() { _state.value = UpdateState.Installing(sampleRelease) }

    fun failVerify() {
        _state.value = UpdateState.Failed(UpdateState.Failed.Stage.VERIFY, "sha256 mismatch")
    }

    fun failDownload() {
        _state.value = UpdateState.Failed(UpdateState.Failed.Stage.DOWNLOAD, "connection reset")
    }

    fun failDiskFull() {
        _state.value = UpdateState.Failed(UpdateState.Failed.Stage.DISK_FULL, "69 MB needed, 12 MB free")
    }

    fun failUntrustedUrl() {
        _state.value = UpdateState.Failed(UpdateState.Failed.Stage.UNTRUSTED_URL, "evil.example.com not in allow-list")
    }

    fun failMalformed() {
        _state.value = UpdateState.Failed(UpdateState.Failed.Stage.MALFORMED_MANIFEST, "version is not semver: 1.0.5-beta")
    }

    fun toggleCallActive() {
        _callState.value = when (_callState.value) {
            is CallState.Idle -> CallState.Active(
                callId = "demo-call",
                remoteNumber = "+998901112233",
                remoteName = "Demo caller",
                isOutbound = false,
                isMuted = false,
                isOnHold = false,
            )
            else -> CallState.Idle
        }
    }

    fun toggleChannel() {
        _channel.value = if (_channel.value == UpdateChannel.STABLE) UpdateChannel.BETA else UpdateChannel.STABLE
    }

    fun cycleLocale() {
        _locale.value = if (_locale.value == "uz") "ru" else "uz"
    }

    fun showDiagnostics() { _diagnosticsVisible.value = true }
    fun hideDiagnostics() { _diagnosticsVisible.value = false }

    /** Emulates UpdateManager.confirmInstall() without calling exitProcess. */
    fun mockInstall() {
        scope.launch {
            _state.value = UpdateState.Installing(sampleRelease)
            val pid = ProcessHandle.current().pid()
            println(
                "[INSTALL HANDOFF] Would have spawned bootstrapper.exe " +
                    "--msi /tmp/fake.msi " +
                    "--expected-sha256 ${sampleRelease.installer.sha256} " +
                    "--parent-pid $pid",
            )
            delay(3_000)
            _state.value = UpdateState.Idle
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileTestKotlin`
Expected: BUILD SUCCESSFUL, no errors.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/uz/yalla/sipphone/demo/update/UpdateDemoDriver.kt
git commit -m "$(cat <<'EOF'
feat(demo): add UpdateDemoDriver with per-state scenario methods

Owns MutableStateFlow<UpdateState> + MutableStateFlow<CallState>
and exposes read-only views for consumption by the production
UpdateBadge / UpdateDialog composables. Each scenario method flips
the flow directly — no UpdateManager, no network, no installer.
mockInstall() emulates confirmInstall() without exitProcess.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: UpdateDemoConsoleLogger

**Files:**
- Create: `src/test/kotlin/uz/yalla/sipphone/demo/update/UpdateDemoConsoleLogger.kt`

- [ ] **Step 1: Write the logger file**

```kotlin
package uz.yalla.sipphone.demo.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.update.UpdateState
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Prints timestamped transitions of [UpdateDemoDriver.state] and
 * [UpdateDemoDriver.callState] to stdout. Same vibe as the existing
 * DemoLogger in the SIP demo.
 */
class UpdateDemoConsoleLogger(
    private val driver: UpdateDemoDriver,
    private val scope: CoroutineScope,
) {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun start() {
        header()

        var previousStateKey: String = ""
        driver.state.onEach { newState ->
            val key = describe(newState)
            if (key != previousStateKey) {
                previousStateKey = key
                log(key)
            }
        }.launchIn(scope)

        var previousCallIdle = true
        driver.callState.onEach { newState ->
            val isIdle = newState is CallState.Idle
            if (isIdle != previousCallIdle) {
                previousCallIdle = isIdle
                log(if (isIdle) "Call ended — install enabled" else "Call became active — install deferred")
            }
        }.launchIn(scope)
    }

    private fun header() {
        println()
        println("=".repeat(60))
        println("  YALLA SIP PHONE \u2014 UPDATE DEMO")
        println("  Every state of the auto-update UI, no network calls.")
        println("=".repeat(60))
        println()
    }

    private fun describe(state: UpdateState): String = when (state) {
        is UpdateState.Idle -> "Idle"
        is UpdateState.Checking -> "Checking for updates..."
        is UpdateState.Downloading -> {
            val percent = if (state.total > 0) (state.bytesRead * 100 / state.total) else 0
            "Downloading v${state.release.version} ($percent%)"
        }
        is UpdateState.Verifying -> "Verifying v${state.release.version}"
        is UpdateState.ReadyToInstall -> "ReadyToInstall v${state.release.version}"
        is UpdateState.Installing -> "Installing v${state.release.version}"
        is UpdateState.Failed -> "Failed(${state.stage}): ${state.reason}"
    }

    private fun log(message: String) {
        println("[${LocalTime.now().format(timeFormat)}] $message")
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/uz/yalla/sipphone/demo/update/UpdateDemoConsoleLogger.kt
git commit -m "$(cat <<'EOF'
feat(demo): add UpdateDemoConsoleLogger for timestamped stdout trace

Collects driver.state and driver.callState flows and prints a
timestamped line on every meaningful transition. Mirrors the style
of the existing SIP DemoLogger so the two demos feel consistent.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: UpdateDemoAutoPlay

**Files:**
- Create: `src/test/kotlin/uz/yalla/sipphone/demo/update/UpdateDemoAutoPlay.kt`

- [ ] **Step 1: Write the auto-play file**

```kotlin
package uz.yalla.sipphone.demo.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scripted walk through the full update state catalog.
 * Supports Pause / Resume / Reset via an AtomicBoolean checked between
 * delay ticks. Total runtime ~40s.
 */
class UpdateDemoAutoPlay(
    private val driver: UpdateDemoDriver,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private val paused = AtomicBoolean(false)

    fun start() {
        if (job?.isActive == true) return
        paused.set(false)
        job = scope.launch {
            runFullScript()
        }
    }

    fun pause() { paused.set(true) }
    fun resume() { paused.set(false) }

    fun reset() {
        job?.cancel()
        job = null
        paused.set(false)
        driver.reset()
    }

    private suspend fun runFullScript() {
        // Act 1 — happy path with call interruption
        driver.reset()
        waitWhilePaused(500)

        driver.showChecking()
        waitWhilePaused(1_500)

        for (p in 0..100 step 10) {
            driver.showDownloading(p)
            waitWhilePaused(500)
        }

        driver.showVerifying()
        waitWhilePaused(1_500)

        driver.showReady()
        waitWhilePaused(3_000)

        driver.toggleCallActive()
        waitWhilePaused(3_000)

        driver.toggleCallActive()
        waitWhilePaused(2_000)

        driver.showInstalling()
        waitWhilePaused(3_000)

        driver.reset()
        waitWhilePaused(1_500)

        // Act 2 — failure catalog
        val failures: List<() -> Unit> = listOf(
            { driver.failMalformed() },
            { driver.failVerify() },
            { driver.failDownload() },
            { driver.failDiskFull() },
            { driver.failUntrustedUrl() },
        )
        for (fail in failures) {
            fail()
            waitWhilePaused(3_000)
            driver.reset()
            waitWhilePaused(1_000)
        }
    }

    private suspend fun waitWhilePaused(totalMs: Long) {
        var remaining = totalMs
        while (remaining > 0 && scope.isActive) {
            delay(100)
            if (!paused.get()) remaining -= 100
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/uz/yalla/sipphone/demo/update/UpdateDemoAutoPlay.kt
git commit -m "$(cat <<'EOF'
feat(demo): add UpdateDemoAutoPlay scripted state walkthrough

Two-act script: happy path (check → download → verify → ready →
call-active defer → installing) then failure catalog (malformed,
verify, download, disk-full, untrusted URL). ~40s total runtime.
Pause/Resume/Reset supported via AtomicBoolean between delay ticks.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: UpdateDemoControlPanel

**Files:**
- Create: `src/test/kotlin/uz/yalla/sipphone/demo/update/UpdateDemoControlPanel.kt`

- [ ] **Step 1: Write the control panel composable**

```kotlin
package uz.yalla.sipphone.demo.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.update.UpdateChannel
import uz.yalla.sipphone.domain.update.UpdateState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateDemoControlPanel(
    driver: UpdateDemoDriver,
    autoPlay: UpdateDemoAutoPlay,
    modifier: Modifier = Modifier,
) {
    val state by driver.state.collectAsState()
    val callState by driver.callState.collectAsState()
    val channel by driver.channel.collectAsState()
    val locale by driver.locale.collectAsState()

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        InspectorLine(state, callState, channel, locale)
        Spacer(Modifier.height(4.dp))

        SectionHeader("Auto-play")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { autoPlay.start() }) { Text("Play All") }
            OutlinedButton(onClick = { autoPlay.pause() }) { Text("Pause") }
            OutlinedButton(onClick = { autoPlay.resume() }) { Text("Resume") }
            OutlinedButton(onClick = { autoPlay.reset() }) { Text("Reset") }
        }

        SectionHeader("Core states")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(onClick = { driver.reset() }) { Text("Idle") }
            OutlinedButton(onClick = { driver.showChecking() }) { Text("Checking") }
            listOf(0, 25, 50, 75, 100).forEach { p ->
                OutlinedButton(onClick = { driver.showDownloading(p) }) { Text("DL $p%") }
            }
            OutlinedButton(onClick = { driver.showVerifying() }) { Text("Verifying") }
            OutlinedButton(onClick = { driver.showReady() }) { Text("Ready") }
            OutlinedButton(onClick = { driver.showInstalling() }) { Text("Installing") }
        }

        SectionHeader("Failures")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(onClick = { driver.failVerify() }) { Text("Verify fail") }
            OutlinedButton(onClick = { driver.failDownload() }) { Text("Download fail") }
            OutlinedButton(onClick = { driver.failDiskFull() }) { Text("Disk full") }
            OutlinedButton(onClick = { driver.failUntrustedUrl() }) { Text("Untrusted URL") }
            OutlinedButton(onClick = { driver.failMalformed() }) { Text("Malformed manifest") }
        }

        SectionHeader("Scenarios")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { driver.toggleCallActive() }) {
                Text(if (callState is CallState.Idle) "Start fake call" else "End fake call")
            }
            OutlinedButton(onClick = { driver.showDiagnostics() }) { Text("Open diagnostics") }
        }

        SectionHeader("Settings")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { driver.cycleLocale() }) { Text("Locale: $locale") }
            OutlinedButton(onClick = { driver.toggleChannel() }) { Text("Channel: ${channel.value}") }
        }
    }
}

@Composable
private fun InspectorLine(
    state: UpdateState,
    callState: CallState,
    channel: UpdateChannel,
    locale: String,
) {
    val stateText = when (state) {
        is UpdateState.Idle -> "Idle"
        is UpdateState.Checking -> "Checking"
        is UpdateState.Downloading -> "Downloading v${state.release.version} (${percent(state.bytesRead, state.total)}%)"
        is UpdateState.Verifying -> "Verifying v${state.release.version}"
        is UpdateState.ReadyToInstall -> "ReadyToInstall v${state.release.version}"
        is UpdateState.Installing -> "Installing v${state.release.version}"
        is UpdateState.Failed -> "Failed(${state.stage}) — ${state.reason}"
    }
    val callText = if (callState is CallState.Idle) "Idle" else "Active"
    Text(
        text = "State: $stateText   Call: $callText   Channel: ${channel.value}   Locale: $locale",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

private fun percent(read: Long, total: Long): Int =
    if (total <= 0) 0 else ((read.toDouble() / total) * 100).toInt().coerceIn(0, 100)
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/uz/yalla/sipphone/demo/update/UpdateDemoControlPanel.kt
git commit -m "$(cat <<'EOF'
feat(demo): add UpdateDemoControlPanel with state catalog buttons

Four-section button grid (Auto-play, Core states, Failures,
Scenarios, Settings) plus a live state inspector line that shows
the current UpdateState, CallState, channel, and locale. Uses
FlowRow for button overflow; opts-in to ExperimentalLayoutApi.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: UpdateDemoMain

**Files:**
- Create: `src/test/kotlin/uz/yalla/sipphone/demo/update/UpdateDemoMain.kt`

- [ ] **Step 1: Write the main entry file**

```kotlin
package uz.yalla.sipphone.demo.update

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import uz.yalla.sipphone.feature.main.update.UpdateBadge
import uz.yalla.sipphone.feature.main.update.UpdateDialog
import uz.yalla.sipphone.feature.main.update.UpdateDiagnosticsDialog
import uz.yalla.sipphone.ui.theme.YallaSipPhoneTheme

/**
 * Standalone visual demo of the auto-update UI. Renders the real
 * UpdateBadge, UpdateDialog, and UpdateDiagnosticsDialog driven by
 * an UpdateDemoDriver. No Koin, no network, no installer process.
 *
 * Run with:  ./gradlew runUpdateDemo
 */
fun main() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val driver = UpdateDemoDriver(scope)
    val autoPlay = UpdateDemoAutoPlay(driver, scope)
    val logger = UpdateDemoConsoleLogger(driver, scope)

    logger.start()

    application {
        val locale by driver.locale.collectAsState()

        val windowState = rememberWindowState(
            size = DpSize(1280.dp, 760.dp),
            position = WindowPosition(Alignment.Center),
        )

        Window(
            onCloseRequest = ::exitApplication,
            title = "DEMO \u2014 Yalla SIP Phone Update UI",
            state = windowState,
            alwaysOnTop = false,
            resizable = true,
        ) {
            YallaSipPhoneTheme(isDark = false, locale = locale) {
                UpdateDemoRoot(driver = driver, autoPlay = autoPlay)
            }
        }
    }
}

@Composable
private fun UpdateDemoRoot(driver: UpdateDemoDriver, autoPlay: UpdateDemoAutoPlay) {
    val diagnosticsVisible by driver.diagnosticsVisible.collectAsState()
    val channel by driver.channel.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top strip: title + badge
            Row(
                modifier = Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "DEMO: Auto-Update UI    (current v${driver.currentVersion})",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                UpdateBadge(
                    state = driver.state,
                    onClick = { println("[demo] badge clicked (no-op — dialog auto-shows)") },
                )
            }

            HorizontalDivider()

            Box(modifier = Modifier.fillMaxSize()) {
                UpdateDemoControlPanel(driver = driver, autoPlay = autoPlay)

                // Production UpdateDialog — auto-renders for any non-idle/checking state
                UpdateDialog(
                    stateFlow = driver.state,
                    callStateFlow = driver.callState,
                    onInstall = { driver.mockInstall() },
                    onDismiss = { driver.reset() },
                )

                UpdateDiagnosticsDialog(
                    visible = diagnosticsVisible,
                    installId = "demo-install-id-0000-1111-2222",
                    channel = channel.value,
                    currentVersion = driver.currentVersion,
                    stateText = driver.state.value::class.simpleName ?: "?",
                    lastCheckText = "never (demo mode)",
                    lastErrorText = "none",
                    logTail = "[demo] no real log tail — this is a mock diagnostics panel",
                    onCopy = { println("[demo] diagnostics copy clicked") },
                    onDismiss = { driver.hideDiagnostics() },
                )
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/uz/yalla/sipphone/demo/update/UpdateDemoMain.kt
git commit -m "$(cat <<'EOF'
feat(demo): wire UpdateDemoMain — real UpdateBadge/Dialog, no JVM exit

Compose Desktop application entry that mounts the real production
UpdateBadge, UpdateDialog, and UpdateDiagnosticsDialog composables
under YallaSipPhoneTheme. Wires them to UpdateDemoDriver so every
StateFlow the production composables consume is driven by the demo
harness instead of the real UpdateManager. Install button calls
driver.mockInstall() which skips exitProcess.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Gradle tasks — rename + add runUpdateDemo

**Files:**
- Modify: `build.gradle.kts:140-150`

- [ ] **Step 1: Rename `runDemo` to `runSipDemo` and add `runUpdateDemo`**

Replace the existing block:

```kotlin
tasks.register<JavaExec>("runDemo") {
    group = "demo"
    description = "Run visual demo with fake SIP engines simulating a busy operator day"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("uz.yalla.sipphone.demo.DemoMainKt")
    jvmArgs(
        "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED",
        "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
    )
}
```

with:

```kotlin
tasks.register<JavaExec>("runSipDemo") {
    group = "demo"
    description = "Run visual demo with fake SIP engines simulating a busy operator day"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("uz.yalla.sipphone.demo.DemoMainKt")
    jvmArgs(
        "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED",
        "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
    )
}

tasks.register<JavaExec>("runUpdateDemo") {
    group = "demo"
    description = "Run visual demo of the auto-update UI — all states, failure modes, and interactions"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("uz.yalla.sipphone.demo.update.UpdateDemoMainKt")
    jvmArgs(
        "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED",
        "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
    )
}
```

- [ ] **Step 2: Verify both tasks are registered**

Run: `./gradlew tasks --group demo`
Expected output contains both:
```
runSipDemo - Run visual demo with fake SIP engines simulating a busy operator day
runUpdateDemo - Run visual demo of the auto-update UI — all states, failure modes, and interactions
```

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts
git commit -m "$(cat <<'EOF'
build(demo): rename runDemo → runSipDemo, add runUpdateDemo task

runSipDemo keeps the existing busy-operator SIP simulation.
runUpdateDemo is a new sibling task pointing at the Compose Desktop
update UI demo entry in uz.yalla.sipphone.demo.update.UpdateDemoMainKt.
Both tasks share the same sourceSets["test"].runtimeClasspath and
--add-opens flags.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Update CLAUDE.md Quick Commands

**Files:**
- Modify: `.claude/CLAUDE.md` — the "Quick Commands" block and the "Manual smoke test" reference

- [ ] **Step 1: Find the current lines**

The file currently has (in the "Quick Commands" section):
```
./gradlew run                            # production-like run (real PJSIP)
./gradlew runDemo                        # visual demo with fake SIP engines
./gradlew run --info                     # verbose output
```

And in the "Manual smoke test" section:
```
- Launch via `./gradlew run` or `./gradlew runDemo`
```

- [ ] **Step 2: Replace Quick Commands block**

Change those three lines to:

```
./gradlew run                            # production-like run (real PJSIP)
./gradlew runSipDemo                     # visual SIP demo (fake engines, busy-operator day)
./gradlew runUpdateDemo                  # visual update-UI demo (all states + failure catalog)
./gradlew run --info                     # verbose output
```

- [ ] **Step 3: Replace Manual smoke test line**

Change:
```
- Launch via `./gradlew run` or `./gradlew runDemo`
```
to:
```
- Launch via `./gradlew run`, `./gradlew runSipDemo`, or `./gradlew runUpdateDemo`
```

- [ ] **Step 4: Commit**

```bash
git add .claude/CLAUDE.md
git commit -m "$(cat <<'EOF'
docs(claude): update Quick Commands for runSipDemo/runUpdateDemo

Reflects the rename of runDemo → runSipDemo and the new
runUpdateDemo task so future sessions pick up the correct task
names from CLAUDE.md's command reference.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: End-to-end verification

**Files:** none modified — this task runs verification commands.

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. No production source was touched, but compileTestKotlin and test tasks run as part of build.

If build fails: read the compiler error, match it to the file/line, fix inline, re-run. Do NOT skip this step.

- [ ] **Step 2: Full test suite still passes**

Run: `./gradlew test`
Expected: All 259 existing tests pass. If any test fails, the demo changes accidentally affected production imports — revert and investigate.

- [ ] **Step 3: Sanity-check both demo tasks exist**

Run: `./gradlew tasks --group demo`
Expected:
```
Demo tasks
----------
runSipDemo - Run visual demo with fake SIP engines simulating a busy operator day
runUpdateDemo - Run visual demo of the auto-update UI — all states, failure modes, and interactions
```

- [ ] **Step 4: Launch runSipDemo as a rename smoke test**

Run: `./gradlew runSipDemo &` (background so the plan can continue; kill manually when window opens)
Expected: The existing toolbar-strip SIP demo opens as before. This confirms the rename didn't break anything.

After ~5 seconds of confirming it works, close the window (or send SIGINT).

- [ ] **Step 5: Launch runUpdateDemo — manual UI verification**

Run: `./gradlew runUpdateDemo &`
Expected: A 1280x760 window opens titled "DEMO — Yalla SIP Phone Update UI" with a top strip ("DEMO: Auto-Update UI (current v1.0.4)"), an inspector line showing `State: Idle  Call: Idle  Channel: stable  Locale: uz`, and a button grid.

Click through the verification checklist:
- [ ] Click "Checking" → inspector shows "Checking", no dialog (badge hidden by design for Idle/Checking)
- [ ] Click "DL 50%" → dialog opens with progress bar ~50%, badge secondary-colored
- [ ] Click "Verifying" → dialog shows verifying message
- [ ] Click "Ready" → dialog shows release notes and Install + Later buttons, badge primary-colored
- [ ] Click "Start fake call" → inspector shows Call: Active, dialog's Install button disables, "waiting for call" text appears
- [ ] Click "End fake call" → Install button re-enables
- [ ] Click "Install" → inspector shows Installing, console prints `[INSTALL HANDOFF] ...`, state returns to Idle after ~3s
- [ ] Click each Failure button (Verify fail, Download fail, Disk full, Untrusted URL, Malformed manifest) → dialog shows the right localized failure text
- [ ] Click "Locale: uz" → button label flips to "Locale: ru", all dialog text switches to Russian
- [ ] Click "Open diagnostics" → diagnostics dialog overlays with mocked install ID, channel, state, last check
- [ ] Click "Play All" → auto-play runs the full catalog; "Pause" freezes it; "Resume" continues; "Reset" returns to Idle
- [ ] Close the window → JVM exits cleanly, no lingering process

If any checklist item fails, note which step, then fix inline (usually a typo in a string, a wrong enum branch, or a missing collectAsState).

- [ ] **Step 6: Final commit (if any fixes were made during step 5)**

If step 5 found issues and you fixed them:

```bash
git add src/test/kotlin/uz/yalla/sipphone/demo/update/
git commit -m "$(cat <<'EOF'
fix(demo): manual verification fixups from runUpdateDemo

Discovered during step-5 click-through of the new demo window.
No production source touched.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```

If no fixes were needed, skip this step and move on.

- [ ] **Step 7: Branch summary**

Run: `git log --oneline main..feature/update-demo`
Expected: A clean sequence of 7–8 atomic commits from the spec through to final verification.

---

## Self-Review

**Spec coverage check:**
- § Architecture (standalone Compose Desktop, test source set, direct StateFlow driver) → Task 1, 5
- § Gradle tasks (rename + new) → Task 6
- § New files (Driver, Logger, AutoPlay, ControlPanel, Main) → Tasks 1–5
- § Modified files (build.gradle.kts, CLAUDE.md) → Tasks 6, 7
- § State catalog (Downloading/Verifying/Ready/Installing + all 5 failure stages) → Task 1 driver methods
- § Behavioral scenarios (call-active defer, no-update silent, locale swap, diagnostics dialog) → Task 1 + Task 4 buttons
- § Window layout (1280x760, top strip + panel, AlertDialog overlay) → Task 5
- § Auto-play script (two-act, ~40s) → Task 3
- § Install click mock (Installing → log → reset, no exitProcess) → Task 1 `mockInstall()`
- § Console logger → Task 2
- § CompositionLocal wiring (via `YallaSipPhoneTheme(locale = ...)` which provides `LocalStrings`) → Task 5
- § Verification (build, test, manual click-through) → Task 8

All spec requirements mapped to tasks.

**Placeholder scan:** No TBDs, no "implement later", every code block contains complete working Kotlin.

**Type consistency:**
- Method names consistent across tasks: `reset()`, `showChecking()`, `showDownloading(percent)`, `showVerifying()`, `showReady()`, `showInstalling()`, `fail{Verify,Download,DiskFull,UntrustedUrl,Malformed}()`, `toggleCallActive()`, `toggleChannel()`, `cycleLocale()`, `showDiagnostics()`, `hideDiagnostics()`, `mockInstall()` — all match between Task 1 (definition) and Task 4 (usage).
- StateFlow names consistent: `state`, `callState`, `channel`, `locale`, `diagnosticsVisible` — match across Tasks 1, 2, 3, 4, 5.
- Package: `uz.yalla.sipphone.demo.update` everywhere. Gradle main class: `uz.yalla.sipphone.demo.update.UpdateDemoMainKt`.
- Field access: `UpdateChannel.value` is the String (`"stable"` / `"beta"`) — used in control panel and diagnostics dialog consistently.

No inconsistencies found.

## Risks captured during planning

- **FlowRow requires `ExperimentalLayoutApi` opt-in** on older Compose Foundation versions. Task 4 adds `@OptIn(ExperimentalLayoutApi::class)`. If Compose 1.8.2 has stabilized FlowRow, the opt-in is inert — still safe.
- **`UpdateDialog` auto-renders** for any non-Idle/Checking state. That means once you click "Ready" the dialog appears and hides the control panel buttons behind it. Dismissing the dialog (Later button or click-outside) calls `driver.reset()` which puts state back to Idle, then you can click more buttons. This is intended — matches production dismiss semantics.
- **`YallaSipPhoneTheme` already provides `LocalStrings`** based on its `locale` parameter. Task 5 re-passes `locale` from `driver.locale.collectAsState()` so Compose recomposes the whole tree when locale flips, which re-evaluates `LocalStrings` correctly without manual CompositionLocalProvider wiring.
- **Uncommitted `libs/pjsua2.jar`** exists in the working tree. Every commit in the plan uses explicit `git add <path>` — never `git add -A` — to avoid accidentally staging the jar.
