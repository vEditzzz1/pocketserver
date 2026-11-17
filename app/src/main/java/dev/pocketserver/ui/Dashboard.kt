package dev.pocketserver.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketserver.engine.Distros
import dev.pocketserver.engine.EngineState
import dev.pocketserver.engine.Net
import dev.pocketserver.engine.RuntimeInstaller
import dev.pocketserver.engine.Permissions
import dev.pocketserver.engine.Podroid
import dev.pocketserver.engine.Prefs
import dev.pocketserver.engine.panel.Panel
import dev.pocketserver.engine.Servers
import dev.pocketserver.engine.Stacks
import dev.pocketserver.ui.theme.Accent
import dev.pocketserver.ui.theme.AccentBorder
import dev.pocketserver.ui.theme.AccentDim
import dev.pocketserver.ui.theme.BorderC
import dev.pocketserver.ui.theme.FgC
import dev.pocketserver.ui.theme.Mono
import dev.pocketserver.ui.theme.MutedC
import dev.pocketserver.ui.theme.Surface1
import dev.pocketserver.ui.theme.Surface2
import dev.pocketserver.ui.theme.WarnC

@Composable
fun DashboardScreen() {
    var showConsole by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        if (showConsole) {
            ConsoleScreen(onClose = { showConsole = false })
        } else {
            MainContent(onConsole = { showConsole = true })
        }
    }
}

@Composable
private fun MainContent(onConsole: () -> Unit) {
    val context = LocalContext.current
    val busy = EngineState.busy.value
    val installed = EngineState.runtimeInstalled.value

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(26.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("PocketServer", style = MaterialTheme.typography.headlineMedium)
                Text("Linux web servers on your phone", style = MaterialTheme.typography.bodySmall, color = MutedC)
            }
            Text(
                "Console",
                color = Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onConsole() }.padding(8.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            StatusCard()
            Spacer(Modifier.height(14.dp))
            ModeSelector()
            if (EngineState.mode.value == EngineState.Mode.Podroid) {
                Spacer(Modifier.height(14.dp))
                PodroidCard()
            }
            Spacer(Modifier.height(14.dp))
            ActionRow(installed = installed, busy = busy, onInstall = {
                // Repair in place: the guest filesystem holds everything the
                // user installed, so it is never wiped from here.
                RuntimeInstaller.install(context)
            }, onReset = {
                RuntimeInstaller.reset(context)
            }, onTest = {
                RuntimeInstaller.runShell(
                    context,
                    Stacks.installCommand(listOfNotNull(Stacks.byId("static"))) +
                        " 2>&1 | tail -2; busybox --list | grep -x httpd && echo HTTPD_READY",
                    "test"
                )
            })
            Spacer(Modifier.height(20.dp))
            ServersSection()
            Spacer(Modifier.height(18.dp))
            ContainersCard()
            Spacer(Modifier.height(14.dp))
            InstallCard()
            Spacer(Modifier.height(14.dp))
            PanelCard()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCard() {
    val installed = EngineState.runtimeInstalled.value
    val context = LocalContext.current
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, BorderC),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            StatusRow("Runtime") {
                Pill(
                    text = if (installed) "Alpine · ready" else "not installed",
                    fg = if (installed) Accent else WarnC,
                    bg = if (installed) AccentDim else Color(0xFF2A1F0A),
                    border = if (installed) AccentBorder else Color(0xFF6B4E14)
                )
            }
            Divider()
            StatusRow("Storage") {
                val allFiles = Permissions.allFilesGranted()
                Text(
                    if (allFiles) "all files" else "tap to grant",
                    fontSize = 12.sp,
                    fontFamily = Mono,
                    color = if (allFiles) Accent else WarnC,
                    modifier = Modifier.clickable {
                        if (!allFiles) Permissions.openAllFilesSettings(context as android.app.Activity)
                    }
                )
            }
            Divider()
            StatusRow("Power") {
                val free = Permissions.ignoringBattery(context)
                Text(
                    if (free) "unrestricted" else "tap to stay awake",
                    fontSize = 12.sp,
                    fontFamily = Mono,
                    color = if (free) Accent else WarnC,
                    modifier = Modifier.clickable {
                        if (!free) Permissions.requestBattery(context as android.app.Activity)
                    }
                )
            }
            Divider()
            StatusRow("Mode") {
                Text(
                    EngineState.mode.value.label,
                    fontSize = 12.sp,
                    fontFamily = Mono,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Divider()
            StatusRow("Sites") {
                val n = Servers.runningCount()
                Text(
                    if (n == 0) "none running" else "$n running",
                    fontSize = 12.sp,
                    fontFamily = Mono,
                    color = if (n == 0) MutedC else Accent
                )
            }
            Divider()
            StatusRow("Packages") {
                val sets = Prefs.stacks
                Text(
                    if (sets.isEmpty()) "none tracked" else "${sets.size} set(s) tracked",
                    fontSize = 12.sp,
                    fontFamily = Mono,
                    color = if (sets.isEmpty()) MutedC else FgC
                )
            }
            Divider()
            StatusRow("Device") {
                Text(
                    "arm64 · Android ${Build.VERSION.RELEASE}",
                    fontSize = 12.sp,
                    fontFamily = Mono,
                    color = MutedC
                )
            }
        }
    }
}

/**
 * Four engine modes now, so the chips are laid out two per row instead of
 * squeezing four labels into one line on a phone.
 *
 * Selection has to move both halves: [EngineState.mode] is what the engine and
 * the status card read, [Prefs.mode] is what survives a restart.
 */
@Composable
private fun ModeSelector() {
    val current = EngineState.mode.value
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EngineState.Mode.entries.chunked(2).forEach { rowModes ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowModes.forEach { mode ->
                    ModeChip(
                        mode = mode,
                        selected = mode == current,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (mode != EngineState.mode.value) {
                                EngineState.mode.value = mode
                                Prefs.mode = mode
                                EngineState.log("mode", "engine → ${mode.name}")
                            }
                        }
                    )
                }
                // Keeps a half-filled row aligned with the full ones.
                if (rowModes.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (current == EngineState.Mode.Podroid && !EngineState.podroidInstalled.value) {
            Text(
                "Podroid VM not installed — use the card below",
                color = WarnC,
                fontSize = 10.sp,
                fontFamily = Mono
            )
        }
    }
}

@Composable
private fun ModeChip(
    mode: EngineState.Mode,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) AccentDim else Surface2,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (selected) AccentBorder else BorderC),
        modifier = modifier.clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                mode.label.substringBefore(" ·"),
                color = if (selected) Accent else FgC,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                mode.label.substringAfter(" · ", ""),
                color = MutedC,
                fontSize = 9.sp,
                fontFamily = Mono
            )
        }
    }
}

@Composable
private fun PodroidCard() {
    val installed = EngineState.podroidInstalled.value
    val running = EngineState.podroidRunning.value
    val busy = EngineState.podroidBusy.value
    // The VM is an Alpine image, so it cannot be the guest while Ubuntu is active.
    val ubuntu = Prefs.distro == Distros.UBUNTU
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, BorderC),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "PODROID VM",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f)
                )
                Pill(
                    text = when {
                        busy -> "working"
                        running -> "running"
                        installed -> "stopped"
                        else -> "not installed"
                    },
                    fg = if (running) Accent else if (busy || !installed) WarnC else FgC,
                    bg = if (running) AccentDim else Surface2,
                    border = if (running) AccentBorder else BorderC
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    busy -> EngineState.busyLabel.value.ifBlank { "working…" }
                    running -> "Alpine on Podroid's UML kernel — site commands run in here, not in PRoot."
                    ubuntu -> "Ubuntu 24.04 is the active distro and this VM runs Alpine, so it stays off. " +
                        "Switch to Alpine 3.24 above to boot it."
                    installed -> "Guest images ready (${Podroid.sizeMb()} MB). Boot runs the kernel " +
                        "through Shizuku, outside the app sandbox."
                    else -> "A real Linux VM using the phone's own kernel, no root. " +
                        "One-time 325 MB download of Podroid's guest images."
                },
                fontSize = 12.sp,
                fontFamily = Mono,
                color = MutedC
            )
            val error = EngineState.podroidError.value
            if (error.isNotEmpty() && !running && !busy) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = WarnC, fontSize = 11.sp, fontFamily = Mono)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    EngineState.podroidError.value = ""
                    if (!installed) {
                        Podroid.install()
                    } else {
                        Thread { if (running) Podroid.stop() else Podroid.boot() }.start()
                    }
                },
                enabled = !busy && !(ubuntu && !running),
                modifier = Modifier.height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) Surface2 else Accent,
                    contentColor = if (running) FgC else Color(0xFF04140A),
                    disabledContainerColor = Surface2,
                    disabledContentColor = MutedC
                )
            ) {
                Text(
                    when {
                        busy -> "Working…"
                        !installed -> "Install VM"
                        running -> "Stop VM"
                        else -> "Boot VM"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onReset: () -> Unit,
    onTest: () -> Unit
) {
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(confirmReset) {
        if (confirmReset) {
            kotlinx.coroutines.delay(5_000)
            confirmReset = false
        }
    }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onInstall,
                enabled = !busy,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color(0xFF04140A),
                    disabledContainerColor = Surface2,
                    disabledContentColor = MutedC
                )
            ) {
                Text(
                    if (busy) EngineState.busyLabel.value
                    else if (installed) "Repair runtime" else "Install runtime",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            OutlinedButton(
                onClick = onTest,
                enabled = !busy && installed,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, BorderC),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = FgC, disabledContentColor = MutedC)
            ) {
                Text("Test shell", fontSize = 13.sp)
            }
        }
        Text(
            if (confirmReset) "tap again to rebuild the guest filesystem"
            else "Reset guest filesystem (packages come back)",
            fontSize = 11.sp,
            fontFamily = Mono,
            color = if (confirmReset) WarnC else MutedC,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy) {
                    if (confirmReset) {
                        confirmReset = false
                        onReset()
                    } else {
                        confirmReset = true
                        EngineState.log("setup", "tap reset again to rebuild the guest filesystem")
                    }
                }
                .padding(vertical = 10.dp)
        )
    }
}

@Composable
private fun ServersSection() {
    val context = LocalContext.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SITES", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(
                "+ New site",
                color = Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable {
                    if (EngineState.runtimeInstalled.value) Servers.newSite(context) else
                        EngineState.log("servers", "install the runtime first")
                }.padding(6.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        if (Servers.profiles.isEmpty()) {
            Surface(color = Surface1, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, BorderC)) {
                Text(
                    "No sites yet. Add one to serve a folder.",
                    color = MutedC,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        Servers.profiles.forEach { profile ->
            val running = Servers.isRunning(profile.id)
            Surface(
                color = Surface1,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, BorderC),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(if (running) Accent else Color(0xFF3A4757))
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (profile.kind == dev.pocketserver.engine.ServerKind.CONTAINER)
                                "Container · ${profile.image.ifBlank { "?" }}"
                            else "${profile.kind.label} · :${profile.port}",
                            fontSize = 11.sp,
                            fontFamily = Mono,
                            color = MutedC
                        )
                    }
                    Text(
                        if (running) "Stop" else "Start",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (running) WarnC else Accent,
                        modifier = Modifier.clickable {
                            if (!EngineState.runtimeInstalled.value) {
                                EngineState.log("servers", "install the runtime first")
                            } else if (running) {
                                Servers.stop(profile)
                            } else {
                                Servers.start(context, profile)
                            }
                        }.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ContainersCard() {
    val context = LocalContext.current
    var image by remember { mutableStateOf("alpine") }
    var command by remember { mutableStateOf("cat /etc/os-release") }
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, BorderC),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("CONTAINERS", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "A Docker image without Docker: udocker pulls it, one PRoot runs it on the app UID.",
                fontSize = 11.sp,
                color = MutedC
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = image,
                onValueChange = { image = it },
                label = { Text("Image", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("Command inside the container", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    if (!EngineState.runtimeInstalled.value) {
                        EngineState.log("containers", "install the runtime first")
                    } else {
                        val site = Servers.newContainerSite(context, image.trim(), command.trim())
                        Servers.start(context, site)
                    }
                },
                enabled = !EngineState.busy.value,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color(0xFF04140A)
                )
            ) {
                Text("Pull & run", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Volumes, environment, entrypoint and the rest are configured per container in the web panel.",
                fontSize = 11.sp,
                color = MutedC
            )
        }
    }
}

@Composable
private fun InstallCard() {
    val context = LocalContext.current
    val busy = EngineState.busy.value
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, BorderC),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("INSTALL", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Text(
                    Distros.label(Prefs.distro),
                    fontSize = 11.sp,
                    fontFamily = Mono,
                    color = if (Prefs.distro == Distros.UBUNTU) Accent else MutedC
                )
            }
            Spacer(Modifier.height(10.dp))
            Stacks.all.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { stack ->
                        Surface(
                            color = Surface2,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderC),
                            modifier = Modifier.weight(1f).clickable(enabled = !busy) {
                                if (EngineState.runtimeInstalled.value) Stacks.install(context, stack)
                                else EngineState.log("pkg", "install the runtime first")
                            }
                        ) {
                            Text(
                                stack.label,
                                fontSize = 11.sp,
                                color = if (busy) MutedC else FgC,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp)
                            )
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DistroChip("Alpine 3.24", Prefs.distro == Distros.ALPINE, Modifier.weight(1f)) {
                    Distros.switchTo(context, Distros.ALPINE)
                }
                DistroChip("Ubuntu 24.04", Prefs.distro == Distros.UBUNTU, Modifier.weight(1f)) {
                    Distros.switchTo(context, Distros.UBUNTU)
                }
            }
        }
    }
}

@Composable
private fun DistroChip(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = if (active) AccentDim else Surface2,
        shape = RoundedCornerShape(100.dp),
        border = BorderStroke(1.dp, if (active) AccentBorder else BorderC),
        modifier = modifier.clickable { onClick() }
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = if (active) Accent else MutedC,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}

@Composable
private fun PanelCard() {
    val context = LocalContext.current
    val running = EngineState.panelRunning.value
    val port = Prefs.panelPort
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, BorderC),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("WEB PANEL", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Pill(
                    text = if (running) "listening :$port" else "stopped",
                    fg = if (running) Accent else MutedC,
                    bg = if (running) AccentDim else Surface2,
                    border = if (running) AccentBorder else BorderC
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (running) Net.lanPanelUrl().substringBefore("?")
                else "Control every site from a browser, on this phone or the LAN.",
                fontSize = 12.sp,
                fontFamily = Mono,
                color = MutedC
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (running) Panel.stop() else Panel.start(context, port)
                    },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (running) Surface2 else Accent,
                        contentColor = if (running) FgC else Color(0xFF04140A)
                    )
                ) {
                    Text(if (running) "Stop panel" else "Start panel", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(Net.panelUrl())
                                )
                            )
                        }
                    },
                    enabled = running,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderC),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = FgC, disabledContentColor = MutedC)
                ) {
                    Text("Open in browser", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ConsoleScreen(onClose: () -> Unit) {
    val lines = EngineState.console
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Console", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "Clear",
                color = MutedC,
                fontSize = 13.sp,
                modifier = Modifier.clickable { EngineState.clear() }.padding(8.dp)
            )
            Text(
                "Back",
                color = Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onClose() }.padding(8.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Surface(
            color = Surface1,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, BorderC),
            modifier = Modifier.fillMaxSize().padding(bottom = 16.dp)
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(12.dp)) {
                items(lines) { line ->
                    Text(
                        line,
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = if (line.contains("ERR")) Color(0xFFFF8A8A) else FgC
                    )
                }
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(BorderC))
}

@Composable
private fun StatusRow(label: String, value: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = MutedC, modifier = Modifier.weight(1f))
        value()
    }
}

@Composable
private fun Pill(text: String, fg: Color, bg: Color, border: Color) {
    Surface(color = bg, shape = RoundedCornerShape(100.dp), border = BorderStroke(1.dp, border)) {
        Text(
            text,
            color = fg,
            fontSize = 11.sp,
            fontFamily = Mono,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
