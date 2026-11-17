package dev.pocketserver.engine

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/**
 * Podroid mode: a real Alpine Linux VM booted with Podroid's User-Mode Linux
 * kernel, instead of PRoot's faked root.
 *
 * Why UML: this phone has no AVF, and UML needs no root — the kernel runs as an
 * ordinary process. Podroid's release APK carries the whole stack (kernel,
 * initrd, guest squashfs, supervisor, QEMU-as-SLIRP-router); it is downloaded
 * on demand because it is 325 MB and not ours to redistribute.
 *
 * The VM itself lives in the [VmService] process, not here: Android's
 * phantom-process policy kills app children that use real memory (measured:
 * SIGKILL 4–11 ms after boot, at every size). This object is the main-process
 * facade — install, port forwards, and the command bridge.
 *
 * Commands reach the guest over **TCP 9099**: `socat` inside the guest forks
 * `/bin/sh` per connection and QEMU's SLIRP router forwards the port to the
 * phone, so one socket per command streams exactly like a local `Process`.
 */
object Podroid {

    /** Guest-side port the command bridge listens on (loopback on the host). */
    const val BRIDGE_PORT = 9099

    private const val TAG = "vm"

    /** Host ports already forwarded into the guest by QEMU's SLIRP router. */
    private val forwarded = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    internal const val RELEASE_URL =
        "https://github.com/ExTV/Podroid/releases/download/v1.3.0/Podroid-v1.3.0-release.apk"

    internal val entries = listOf(
        "lib/arm64-v8a/libpodroid-launcher.so" to "libpodroid-launcher.so",
        "lib/arm64-v8a/libpodroid-uml.so" to "libpodroid-uml.so",
        "lib/arm64-v8a/libqemu-system-aarch64.so" to "libqemu-system-aarch64.so",
        "lib/arm64-v8a/libslirp.so" to "libslirp.so",
        "lib/arm64-v8a/libumlstub-4k.so" to "libumlstub-4k.so",
        "lib/arm64-v8a/libuml-4k.so" to "libuml-kernel.so",
        "assets/initrd.img" to "initrd.img",
        "assets/alpine-rootfs.squashfs" to "alpine-rootfs.squashfs"
    )

    fun appContext(): Context? = Prefs.context()

    val dir: File get() = File(Paths.files, "podroid")
    internal val apk: File get() = File(dir, "podroid.apk")
    internal val kernel: File get() = File(dir, "libuml-kernel.so")
    internal val initrd: File get() = File(dir, "initrd.img")
    internal val squashfs: File get() = File(dir, "alpine-rootfs.squashfs")
    internal val supervisor: File get() = File(dir, "libpodroid-uml.so")
    internal val launcher: File get() = File(dir, "libpodroid-launcher.so")
    internal val router: File get() = File(dir, "libqemu-system-aarch64.so")
    internal val stub: File get() = File(dir, "libumlstub-4k.so")
    internal val storage: File get() = File(dir, "storage.img")
    internal val terminal: File get() = File(dir, "terminal.sock")
    internal val tmpDir: File get() = File(dir, "uml-tmp")

    fun installed(): Boolean = kernel.exists() && initrd.exists() && squashfs.exists() &&
        supervisor.exists() && launcher.exists() && router.exists() && stub.exists()

    fun running(): Boolean = EngineState.podroidRunning.value

    // ------------------------------------------------------------- install

    /**
     * Downloads Podroid's release APK and keeps only the VM files, then throws
     * the 325 MB zip away: the guest images are what actually get used.
     */
    fun install(onDone: (Boolean) -> Unit = {}) {
        if (EngineState.podroidBusy.value) {
            EngineState.log(TAG, "already busy: ${EngineState.busyLabel.value}")
            onDone(false)
            return
        }
        thread(name = "podroid-install") {
            EngineState.podroidBusy.value = true
            EngineState.busyLabel.value = "Downloading Podroid VM"
            var ok = false
            try {
                dir.mkdirs()
                ok = download() && extract() && prepare()
            } catch (t: Throwable) {
                EngineState.log(TAG, "ERR install: ${t.message}")
            } finally {
                runCatching { if (apk.exists()) apk.delete() }
                EngineState.podroidInstalled.value = installed()
                EngineState.podroidBusy.value = false
                EngineState.busyLabel.value = ""
                if (ok) EngineState.log(TAG, "VM installed (${sizeMb()} MB)")
                onDone(ok)
            }
        }
    }

    private fun download(): Boolean {
        EngineState.log(TAG, "downloading Podroid release (~325 MB, one time)")
        val conn = (URL(RELEASE_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "PocketServer")
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            apk.outputStream().use { output ->
                val buf = ByteArray(1 shl 20)
                var written = 0L
                var nextMark = 0.1
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    written += n
                    if (total > 0) {
                        val pct = written.toDouble() / total
                        if (pct >= nextMark) {
                            EngineState.log(TAG, "download ${(pct * 100).toInt()}% (${written / (1 shl 20)} MB)")
                            nextMark += 0.1
                        }
                    }
                }
                EngineState.log(TAG, "downloaded ${written / (1 shl 20)} MB")
            }
        }
        conn.disconnect()
        return apk.length() > 100L * 1024 * 1024
    }

    private fun extract(): Boolean {
        EngineState.log(TAG, "extracting guest images")
        var done = 0
        ZipFile(apk).use { zip ->
            entries.forEach { (entryName, targetName) ->
                val entry = zip.getEntry(entryName)
                if (entry == null) {
                    EngineState.log(TAG, "ERR ${entryName} missing from the APK")
                    return false
                }
                val target = File(dir, targetName)
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
                }
                if (targetName.endsWith(".so")) target.setExecutable(true, false)
                done++
                EngineState.log(TAG, "$targetName (${target.length() / (1 shl 20)} MB)")
            }
        }
        prepareDirs()
        return done == entries.size
    }

    /** storage.img is the persistent ext4 overlay; the guest formats it on boot. */
    private fun prepare(): Boolean {
        if (!storage.exists()) {
            RandomAccessFile(storage, "rw").use { it.setLength(2L * 1024 * 1024 * 1024) }
            EngineState.log(TAG, "created storage.img (2 GB overlay)")
        }
        return installed()
    }

    private fun prepareDirs() {
        dir.mkdirs()
        tmpDir.mkdirs()
        File(dir, "hostfs").mkdirs()
    }

    /** Guest images on disk, in MB — shown on the dashboard (sparse overlay excluded). */
    fun sizeMb(): Long = dir.listFiles()
        ?.filter { it.name != "storage.img" && it.name != "podroid.apk" }
        ?.sumOf { it.length() }
        ?.div(1 shl 20) ?: 0L

    // ------------------------------------------------------------ lifecycle

    /** Asks the `:vm` service to boot. Returns immediately; state arrives by bus. */
    fun boot() {
        val ctx = appContext() ?: return
        // The VM is an Alpine image. With Ubuntu selected it cannot be the guest,
        // and letting it boot would take every command away from the distro.
        if (Prefs.distro == Distros.UBUNTU) {
            EngineState.podroidError.value =
                "Ubuntu 24.04 is the active distro, and the Podroid VM is Alpine 3.24. " +
                    "Switch to Alpine 3.24 to run the VM."
            EngineState.log(TAG, "not booting: Ubuntu is the selected distro")
            return
        }
        prepareDirs()
        VmService.boot(ctx)
    }

    /**
     * Stops the VM and waits for the kernel to be gone. Callers that are about to
     * hand the site ports and the bridge to another userland need the real exit,
     * not the flag: `running()` goes false before the processes do.
     */
    fun stopAndWait(timeoutMs: Long = 45_000) {
        VmRunner.stop()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !VmRunner.bridgeFree()) {
            Thread.sleep(300)
        }
    }

    fun stop() {
        appContext()?.let { VmService.stop(it) }
    }

    /**
     * Stops the VM and boots it again. Waits until the kernel, its supervisor and
     * the QEMU router are really gone: booting while a stop is still in flight
     * makes the new boot adopt the dying kernel, and the guest never comes back.
     */
    fun reboot() {
        EngineState.podroidError.value = ""
        // Stop synchronously and wait for the guest to let go of its sockets:
        // a boot that starts while the old kernel still holds 9099 fails on its
        // hostfwd rule and leaves the VM down.
        VmRunner.stop()
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && !VmRunner.bridgeFree()) {
            Thread.sleep(400)
        }
        Thread.sleep(800)
        boot()
    }

    /**
     * Runs [command] inside the guest, booting the VM when needed — a site start
     * must not fail just because the machine was idle.
     */
    fun start(command: String, workdir: String = "/root"): Process {
        if (!running()) {
            // Do not boot a VM for every site start once this device has shown
            // it kills the guest: the caller falls back to PRoot immediately.
            if (EngineState.podroidError.value.isNotEmpty()) {
                throw IllegalStateException("Podroid VM unavailable on this device")
            }
            EngineState.log(TAG, "VM not running — booting for: ${command.take(60)}")
            // The :vm service boots in another process, so state arrives by bus.
            // Abort the moment it reports failure: waiting the whole deadline for
            // a guest that already died just leaves the site spinning silently.
            boot()
            val deadline = System.currentTimeMillis() + 180_000
            while (!running() && EngineState.podroidError.value.isEmpty() &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(250)
            }
            if (!running()) {
                throw IllegalStateException(
                    EngineState.podroidError.value.ifBlank { "Podroid VM did not start" }
                )
            }
            // The guest can be SIGKILLed a few milliseconds *after* the bridge
            // answers (measured here: "Ready!" → kernel exited 137 in ~30 ms),
            // so settle briefly before handing the command to a VM that is
            // already gone — otherwise the site start goes quiet: no console
            // line, no PRoot fallback, nothing listening on the port.
            val settle = System.currentTimeMillis() + 750
            while (running() && System.currentTimeMillis() < settle) Thread.sleep(25)
            if (!running()) {
                throw IllegalStateException(
                    EngineState.podroidError.value.ifBlank { "Podroid VM died right after boot" }
                )
            }
        }
        val line = if (workdir.isNotBlank() && workdir != "/" && workdir != "/root") {
            "cd '$workdir' 2>/dev/null; $command"
        } else command
        return PodroidProcess(line)
    }

    // ------------------------------------------------------------ forwards

    /**
     * Loopback command bridge plus the ports sites actually need.
     *
     * QEMU parses `-netdev` with QemuOpts, which silently caps the option string
     * around 1 KB — 120 pre-made forwards overflowed it and QEMU refused to
     * start. So only the ports that exist now are forwarded; [ensureForward]
     * adds the rest at runtime over QMP.
     */
    internal fun qemuArgs(): String {
        val sb = StringBuilder("ipv6=off")
        sb.append(",hostfwd=tcp:127.0.0.1:$BRIDGE_PORT-:$BRIDGE_PORT")
        forwarded.clear()
        forwarded += BRIDGE_PORT
        val wanted = linkedSetOf<Int>()
        // The panel is a *host* server: forwarding its port into the guest buys
        // nothing and, once QEMU owns it, the panel cannot bind while the VM
        // runs ("EADDRINUSE" and requests land in the guest instead).
        runCatching { Servers.profiles.forEach { if (it.enabled) wanted += it.port } }
        for (port in wanted) {
            if (port in forwarded || port !in 1024..65535) continue
            if (!portFree(port)) {
                // Something on the phone (a PRoot site, the panel) already owns
                // it; QEMU refuses to *start at all* over one bad rule.
                EngineState.log(TAG, "port $port busy on the phone — not forwarding it")
                continue
            }
            sb.append(",hostfwd=tcp::$port-:$port")
            forwarded += port
        }
        return sb.toString().also { saveForwards() }
    }

    /**
     * True when nothing listens on [port]. `/proc` is consulted first: a listener
     * on the IPv6 wildcard does not always make an IPv4 bind fail, yet QEMU's
     * hostfwd refuses it — and **one** refused rule keeps the whole VM from
     * starting (measured: a leftover PRoot site on 8080 stopped the boot).
     */
    private fun portFree(port: Int): Boolean {
        if (listeningOn(port)) return false
        return runCatching {
            java.net.ServerSocket().use {
                it.reuseAddress = false
                it.bind(java.net.InetSocketAddress(port))
            }
            true
        }.getOrDefault(false)
    }

    private fun listeningOn(port: Int): Boolean {
        val suffix = ":%04X".format(port)
        return listOf("/proc/net/tcp", "/proc/net/tcp6").any { path ->
            runCatching {
                File(path).readLines().drop(1).any { line ->
                    val fields = line.trim().split(Regex("\\s+"))
                    fields.size > 3 && fields[1].endsWith(suffix) && fields[3] == "0A"
                }
            }.getOrDefault(false)
        }
    }

    /** Ports currently forwarded host→guest (read-only view for the panel). */
    fun forwardedPorts(): Set<Int> = forwarded.toSet()

    /** Records the forwards so an adopted VM still knows what QEMU holds. */
    private fun saveForwards() {
        runCatching {
            File(dir, "forwards.txt").writeText(forwarded.sorted().joinToString(","))
        }
    }

    internal fun loadForwards() {
        runCatching {
            val text = File(dir, "forwards.txt").readText()
            forwarded.clear()
            text.split(',').mapNotNull { it.trim().toIntOrNull() }.forEach { forwarded += it }
        }
    }

    /** Forgets the forward list: the VM is gone, so nothing is forwarded. */
    internal fun clearForwards() {
        forwarded.clear()
        runCatching { File(dir, "forwards.txt").delete() }
    }

    /** The kernel's pid on the host side (0 when it runs in-process or stopped). */
    fun pid(): Long = VmRunner.pid()

    /**
     * One QMP session, one HMP command. QEMU's `-netdev` string cannot grow at
     * runtime, so forwards are added and removed through this socket.
     */
    private fun qmpHmp(command: String): Boolean = runCatching {
        val qmp = File(dir, "qmp.sock")
        if (!qmp.exists()) return false
        LocalSocket().use { socket ->
            socket.connect(
                LocalSocketAddress(qmp.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)
            )
            val out = socket.outputStream
            val input = socket.inputStream.bufferedReader()
            input.readLine() // QMP greeting
            fun send(cmd: String): String {
                out.write((cmd + "\n").toByteArray())
                out.flush()
                return input.readLine() ?: ""
            }
            send("""{"execute":"qmp_capabilities"}""")
            val reply = send(
                """{"execute":"human-monitor-command","arguments":{"command-line":"$command"}}"""
            )
            !reply.contains("\"error\"")
        }
    }.getOrDefault(false)

    /** Adds a host→guest forward while the VM runs, through QEMU's QMP socket. */
    fun ensureForward(port: Int) {
        if (port !in 1024..65535) return
        if (port in forwarded) return
        if (!running()) return
        if (!qmpHmp("hostfwd_add tcp::$port-:$port")) {
            // Usually an adopted VM that already had the rule (the in-memory
            // list is empty after a restart); keep the list honest.
            EngineState.log(TAG, "port $port already forwarded to the VM")
            forwarded += port
            return
        }
        forwarded += port
        EngineState.log(TAG, "forwarded port $port to the VM")
        saveForwards()
    }

    /**
     * Takes a forward back (used when the panel needs its port while the VM
     * runs, and for a QEMU that grabbed a site port before the site started).
     */
    /**
     * Takes a forward back. The in-memory list is empty after an adopted VM, so
     * the QMP call is attempted whenever the VM runs.
     */
    fun releaseForward(port: Int): Boolean {
        // Do not trust EngineState.podroidRunning here: the app may have
        // restarted while the VM kept running. The QMP socket is the truth.
        if (!File(dir, "qmp.sock").exists()) return false
        if (!qmpHmp("hostfwd_remove tcp::$port")) return false
        forwarded -= port
        EngineState.log(TAG, "released port $port from the VM")
        saveForwards()
        return true
    }

    /** One-shot command over the bridge, used by the boot sequence. */
    internal fun rawExec(command: String, timeoutMs: Long): Pair<Int, String> {
        val socket = Socket()
        val sb = StringBuilder()
        var code = -1
        try {
            socket.connect(InetSocketAddress("127.0.0.1", BRIDGE_PORT), 8000)
            socket.soTimeout = timeoutMs.toInt()
            socket.getOutputStream().apply {
                write((command + "\n").toByteArray())
                flush()
            }
            val reader = socket.getInputStream().bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                val clean = line.trimEnd('\r')
                if (clean.startsWith("__PS_RC=")) {
                    code = clean.removePrefix("__PS_RC=").trim().toIntOrNull() ?: -1
                    break
                }
                sb.append(clean).append('\n')
            }
        } finally {
            runCatching { socket.close() }
        }
        return code to sb.toString()
    }

    /** `Process` view of one guest command, streamed over the TCP bridge. */
    private class PodroidProcess(private val command: String) : Process() {

        private val sink = java.io.PipedOutputStream()
        private val stream = java.io.PipedInputStream(sink, 64 * 1024)
        @Volatile private var exit: Int? = null
        @Volatile private var socket: Socket? = null

        private val worker = thread(name = "podroid-exec") { run() }

        private fun run() {
            var code = -1
            try {
                val s = Socket()
                s.connect(InetSocketAddress("127.0.0.1", BRIDGE_PORT), 8000)
                socket = s
                s.getOutputStream().apply {
                    write((command + "\n").toByteArray())
                    flush()
                }
                val reader = s.getInputStream().bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    val clean = line.trimEnd('\r')
                    if (clean.startsWith("__PS_RC=")) {
                        code = clean.removePrefix("__PS_RC=").trim().toIntOrNull() ?: -1
                        break
                    }
                    if (clean.isEmpty()) continue
                    sink.write((clean + "\n").toByteArray())
                    sink.flush()
                }
            } catch (t: Throwable) {
                runCatching { sink.write(("ERR ${t.message}\n").toByteArray()) }
            } finally {
                exit = code
                runCatching { socket?.close() }
                runCatching { sink.close() }
            }
        }

        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) = Unit
        }

        override fun getInputStream(): InputStream = stream

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            worker.join()
            return exitValue()
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            worker.join(unit.toMillis(timeout))
            return !worker.isAlive
        }

        override fun exitValue(): Int =
            exit ?: throw IllegalThreadStateException("command still running")

        override fun destroy() {
            // Closing the socket makes socat kill the guest-side process group.
            runCatching { socket?.close() }
            worker.interrupt()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }

        override fun isAlive(): Boolean = worker.isAlive
    }
}

/**
 * The engine proper, alive only inside the `:vm` process ([VmService]).
 * Android will not let the main process keep a child this large.
 */
internal object VmRunner {

    private const val TAG = "vm"

    /**
     * Guest RAM. The VM is one process, so this is its whole heap: 768 MB runs
     * Alpine, apk and a web server comfortably.
     */
    private const val MEM_MB = 768
    private const val CPUS = 2

    /** Shown when a kernel that was started outside the app dies on its own. */
    private const val VM_STOPPED =
        "The Podroid VM stopped. Sites keep running through PRoot."

    /**
     * Shown when the kernel could not be started at all. A kernel forked from an
     * app process inherits the zygote seccomp filter and is SIGKILLed 7–30 ms
     * after boot (measured); the launch that works goes through Shizuku.
     */
    const val PHANTOM_HINT =
        "Podroid needs Shizuku to start its kernel outside the app sandbox — an app " +
            "process cannot keep the VM up. Sites keep running through PRoot."

    private var process: Process? = null

    /** Pid of a kernel started through root/Shizuku (0 = started in-process). */
    @Volatile private var hostPid = 0L

    /** True once the kernel was started outside this process. */
    @Volatile private var hostLaunch = false

    /** Kernel pid for the dashboard and the panel. */
    fun pid(): Long = hostPid

    fun boot(): Boolean {
        if (EngineState.podroidRunning.value && alive()) return true
        if (!Podroid.installed()) {
            VmBus.log(TAG, "VM files missing — install Podroid first")
            VmBus.error("The VM images are not installed yet.")
            return false
        }
        Podroid.dir.mkdirs()
        Podroid.tmpDir.mkdirs()
        // A stale failure from an earlier boot must not make Shell.start refuse
        // the VM once this boot succeeds.
        EngineState.podroidError.value = ""
        return try {
            // A kernel started by an earlier `:vm` life keeps running on purpose;
            // adopt it instead of booting a second one onto the same sockets.
            VmBus.log(TAG, "starting the kernel through Shizuku")
            // A kernel that is still shutting down holds the bridge port; wait
            // for it, or the launch dies on its own hostfwd rule.
            var waited = 0
            while (!bridgeFree() && waited < 15_000) {
                Thread.sleep(500)
                waited += 500
            }
            val started = adoptHostVm() ||
                if (Shell.canHostVm()) startKernelHost() else startKernel()
            val ok = started && waitForTerminal() && setupBridge() && waitForBridge()
            if (!ok) VmBus.error(if (hostLaunch) VM_STOPPED else PHANTOM_HINT)
            ok
        } catch (t: Throwable) {
            VmBus.log(TAG, "ERR boot: ${t.message}")
            stop()
            VmBus.error(if (hostLaunch) VM_STOPPED else PHANTOM_HINT)
            false
        }
    }

    /** Kernel + supervisor + router argv, shared by both launch paths. */
    private fun launcherArgv(): List<String> {
        val cmd = mutableListOf(
            Podroid.launcher.absolutePath,
            Podroid.supervisor.absolutePath,
            Podroid.router.absolutePath,
            Podroid.dir.absolutePath,
            "--",
            Podroid.kernel.absolutePath,
            "mem=${MEM_MB}M",
            "ncpus=$CPUS",
            "seccomp=on",
            "initrd=${Podroid.initrd.absolutePath}",
            "ubd0=${Podroid.storage.absolutePath}",
            "ubd1r=${Podroid.squashfs.absolutePath}",
            "podroid.backend=uml",
            "podroid.tty=ttyS0",
            "podroid.ncpus=$CPUS",
            "podroid.x11.dpi=160",
            "hostfs=/storage/emulated/0"
        )
        RuntimeInstaller.suggestedDnsOrEmpty().takeIf { it.isNotEmpty() }?.let {
            cmd += "podroid.dns=${it.joinToString(",")}"
        }
        return cmd
    }

    private fun launcherEnv(): Map<String, String> = linkedMapOf(
        "LD_LIBRARY_PATH" to Podroid.dir.absolutePath,
        "TMPDIR" to Podroid.tmpDir.absolutePath,
        "HOME" to Podroid.tmpDir.absolutePath,
        "PODROID_UML_STUB" to Podroid.stub.absolutePath,
        "PODROID_UML_QEMU_ARGS" to Podroid.qemuArgs()
    )

    /** A previous boot leaves socket files behind; the supervisor cannot bind over them. */
    private fun clearSockets() {
        listOf("terminal.sock", "serial.sock", "ctrl.sock", "host.sock", "qmp.sock")
            .forEach { runCatching { File(Podroid.dir, it).delete() } }
    }

    /**
     * Starts the kernel through root/Shizuku with `run-as`, so it is **not** a
     * child of an app process and no zygote seccomp filter applies to it.
     *
     * This is the difference between a VM that works and one that dies: as an
     * app child the guest is SIGKILLed 7–30 ms after "Ready!" (measured), while
     * the identical command started from a shell runs until it is stopped.
     */
    private fun startKernelHost(): Boolean {
        val pkg = Podroid.appContext()?.packageName ?: return false
        val dir = Podroid.dir
        val script = File(dir, "launch.sh")
        val log = File(dir, "vm.log")
        val pidFile = File(dir, "vm.pid")
        val exports = launcherEnv().entries.joinToString("\n") { (k, v) -> "export $k=${q(v)}" }
        val argv = launcherArgv().joinToString(" ") { q(it) }
        script.writeText(
            buildString {
                append("#!/system/bin/sh\n")
                append("# Written by PocketServer. Starts the Podroid UML kernel outside the\n")
                append("# app process: from inside, the zygote seccomp filter kills it.\n")
                append("D=${q(dir.absolutePath)}\n")
                append("cd \"\$D\" || exit 3\n")
                append("rm -f terminal.sock serial.sock ctrl.sock host.sock qmp.sock\n")
                append(exports).append('\n')
                // The launcher must run as a *child* of this script, not through
                // `exec`: when the script's process became the launcher itself,
                // the kernel vanished silently 100 ms later (measured). One extra
                // shell layer keeps it alive, so wait for it here.
                append("( $argv ) > \"\$D/vm.log\" 2>&1 < /dev/null &\n")
                append("echo \$! > \"\$D/vm.pid\"\n")
                append("wait \$!\n")
            }
        )
        runCatching {
            script.setReadable(true, false)
            script.setExecutable(true, false)
        }
        pidFile.delete()
        log.delete()
        VmBus.log(TAG, "starting UML kernel through the shell (${MEM_MB} MB, $CPUS cpu)")
        val launch = "run-as $pkg sh -c " +
            "\"(setsid sh ${q(script.absolutePath)} > ${q(log.absolutePath)} 2>&1 < /dev/null &); " +
            "echo LAUNCHED\" 2>&1"
        val (code, out) = Shell.shizukuExec(launch)
        if (pidFile.length() == 0L) {
            // The script writes vm.pid before exec, so a missing file means the
            // launch never ran. Probe `run-as` itself to say which link broke.
            val (pc, pout) = Shell.shizukuExec("run-as $pkg sh -c 'echo PROBE_OK; id' 2>&1")
            VmBus.log(TAG, "run-as probe (exit $pc): ${pout.trim().replace('\n', ' ').take(220)}")
        }
        val pid = runCatching { pidFile.readText().trim().toLong() }.getOrDefault(0L)
        // The supervisor's console socket is the real "kernel is up" signal.
        // `/proc` lookups are not used for this: a process launched through
        // `run-as` runs in another SELinux domain.
        val deadline = System.currentTimeMillis() + 20_000
        while (!Podroid.terminal.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(300)
        }
        val up = Podroid.terminal.exists()
        VmBus.log(
            TAG,
            "host launch exit $code, pid=$pid, console=$up, log=${logPreview(log)}" +
                if (out.isNotBlank()) ", shell=${out.trim().take(120)}" else ""
        )
        if (!up || pid <= 0) return false
        hostPid = pid
        hostLaunch = true
        attachConsole()
        tailKernelLog(log)
        watchHost(pid)
        return true
    }

    private fun logPreview(log: File): String =
        runCatching { log.readText().trim().replace('\n', ' ').take(220) }.getOrDefault("")

    /** Takes over a kernel that outlived a previous app process. */
    private fun adoptHostVm(): Boolean = adoptExisting()

    /**
     * Attaches to a kernel started by an earlier app life. Without this the app
     * (and the web panel) would show "stopped" while the VM is up, and the panel
     * could not reclaim its port from QEMU.
     */
    fun adoptExisting(): Boolean {
        if (hostPid > 0) return true
        val pid = runCatching { File(Podroid.dir, "vm.pid").readText().trim().toLong() }
            .getOrDefault(0L)
        if (pid <= 0 || !processAlive(pid)) return false
        hostPid = pid
        hostLaunch = true
        Podroid.loadForwards()
        EngineState.podroidRunning.value = true
        VmBus.log(TAG, "adopted the running kernel (pid $pid)")
        attachConsole()
        watchHost(pid)
        VmBus.state(running = true, busy = false, label = "")
        return true
    }

    /** Follows the kernel's stdout, which a host launch writes to `vm.log`. */
    private fun tailKernelLog(log: File) {
        thread(name = "podroid-log") {
            var offset = 0L
            val pending = StringBuilder()
            while (alive()) {
                runCatching {
                    if (log.length() > offset) {
                        val bytes = ByteArray((log.length() - offset).toInt())
                        val read = java.io.RandomAccessFile(log, "r").use { rf ->
                            rf.seek(offset)
                            rf.read(bytes)
                        }
                        if (read > 0) {
                            offset += read
                            pending.append(String(bytes, 0, read))
                            while (true) {
                                val nl = pending.indexOf("\n")
                                if (nl < 0) break
                                val line = pending.substring(0, nl).trim()
                                pending.delete(0, nl + 1)
                                if (line.isNotEmpty()) VmBus.log(TAG, line)
                            }
                        }
                    }
                }
                Thread.sleep(1_000)
            }
        }
    }

    /** Reports a host-launched kernel that exits on its own. */
    private fun watchHost(pid: Long) {
        thread(name = "podroid-watch") {
            while (hostPid == pid) {
                Thread.sleep(3_000)
                if (hostPid != pid) return@thread // stopped on purpose
                if (processAlive(pid)) continue
                hostPid = 0
                runCatching { File(Podroid.dir, "vm.pid").delete() }
                VmBus.log(TAG, "kernel exited")
                EngineState.podroidRunning.value = false
                Podroid.clearForwards()
                VmBus.error(VM_STOPPED)
                VmBus.state(running = false, busy = false, label = "")
                return@thread
            }
        }
    }

    /**
     * Liveness of a host-launched kernel. `/proc` first (cheap), then a real
     * `kill -0` through the shell: the kernel runs in another SELinux domain, so
     * reading its `/proc` entry is not guaranteed.
     */
    private fun processAlive(pid: Long): Boolean {
        if (pidAlive(pid)) return true
        val pkg = Podroid.appContext()?.packageName ?: return false
        return Shell.shizukuExec("run-as $pkg kill -0 $pid 2>/dev/null", 8_000).first == 0
    }

    /** Pids of the VM's own processes — same UID, so `/proc` is readable. */
    /**
     * Pids of the VM's processes, listed from inside the shell: the VM runs in
     * another SELinux domain, so a `/proc` scan from the app can come up empty.
     * The shell's own pid is skipped, since its command line contains the
     * patterns it searches for.
     */
    /** True when no VM process is left anywhere — the shell sees other domains. */
    fun vmGone(): Boolean = hostVmPids().isEmpty()

    /** True when the loopback bridge port can be bound again. */
    fun bridgeFree(): Boolean = runCatching {
        java.net.ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(java.net.InetSocketAddress("127.0.0.1", Podroid.BRIDGE_PORT))
        }
        true
    }.getOrDefault(false)

    private fun hostVmPids(): List<Long> {
        val pkg = Podroid.appContext()?.packageName ?: return emptyList()
        val script = "for d in /proc/[0-9]*; do p=\${d#/proc/}; " +
            "[ \"\$p\" = \"\$\$\" ] && continue; " +
            "c=\$(tr '\\0' ' ' < \$d/cmdline 2>/dev/null); " +
            "case \"\$c\" in *libuml-kernel.so*|*libqemu-system-aarch64.so*|" +
            "*libpodroid-launcher.so*) echo \$p;; esac; done"
        val out = Shell.shizukuExec("run-as $pkg sh -c \"$script\"").second
        return out.lineSequence().mapNotNull { it.trim().toLongOrNull() }.toList()
    }

    private fun pidAlive(pid: Long): Boolean =
        runCatching { File("/proc/$pid/stat").readText().isNotEmpty() }.getOrDefault(false)

    /** True while the VM runs, whichever way it was started. */
    private fun alive(): Boolean {
        val pid = hostPid
        return if (pid > 0) processAlive(pid) else process?.isAlive == true
    }

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun startKernel(): Boolean {
        val dir = Podroid.dir
        val builder = ProcessBuilder(launcherArgv())
        builder.directory(dir)
        builder.environment().putAll(launcherEnv())
        builder.redirectErrorStream(true)
        clearSockets()
        VmBus.log(TAG, "starting UML kernel (${MEM_MB} MB, $CPUS cpu)")
        val p = builder.start()
        process = p
        attachConsole()
        thread(name = "podroid-log") {
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) VmBus.log(TAG, line.trim())
                }
            }
            val code = runCatching { p.exitValue() }.getOrDefault(-1)
            VmBus.log(TAG, "kernel exited ($code)")
            EngineState.podroidRunning.value = false
            // A guest that dies on its own (not via stop()) leaves the app
            // thinking the VM works; say why instead of silently going "stopped".
            if (process === p) VmBus.error(PHANTOM_HINT)
            EngineState.podroidRunning.value = false
            VmBus.state(running = false, busy = false, label = "")
            process = null
        }
        return true
    }

    /**
     * Follows the kernel console (`serial.sock`, con0). Without a reader the
     * supervisor only keeps a 64 KB ring buffer that dies with the process, so a
     * guest that shuts itself down leaves no explanation — measured here, the
     * kernel SIGKILLs the whole process group and the reason is only on con0.
     */
    private fun attachConsole() {
        thread(name = "podroid-console") {
            val path = File(Podroid.dir, "serial.sock")
            var socket: LocalSocket? = null
            val pending = StringBuilder()
            var last = ""
            while (hostPid > 0 || process?.isAlive == true) {
                if (socket == null) {
                    if (!path.exists()) {
                        Thread.sleep(300)
                        continue
                    }
                    socket = runCatching {
                        LocalSocket().apply {
                            connect(
                                LocalSocketAddress(
                                    path.absolutePath,
                                    LocalSocketAddress.Namespace.FILESYSTEM
                                )
                            )
                        }
                    }.getOrNull()
                    if (socket == null) {
                        Thread.sleep(400)
                        continue
                    }
                }
                val read = runCatching {
                    val n = socket!!.inputStream.available()
                    if (n <= 0) 0 else {
                        val buf = ByteArray(n)
                        val got = socket!!.inputStream.read(buf, 0, n)
                        if (got > 0) pending.append(String(buf, 0, got))
                        got
                    }
                }.getOrDefault(-1)
                if (read < 0) break
                if (read == 0) {
                    Thread.sleep(150)
                    continue
                }
                while (true) {
                    val nl = pending.indexOf("\n")
                    if (nl < 0) break
                    val line = pending.substring(0, nl).trimEnd('\r')
                    pending.delete(0, nl + 1)
                    if (line.isNotBlank() && line != last) {
                        last = line
                        VmBus.log("vm-con", line)
                    }
                }
            }
            runCatching { socket?.close() }
        }
    }

    private fun waitForTerminal(): Boolean {
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            if (!Podroid.terminal.exists()) {
                if (!alive()) {
                    VmBus.log(TAG, "ERR kernel died before the console appeared")
                    return false
                }
                Thread.sleep(500)
                continue
            }
            Thread.sleep(1500)
            return true
        }
        VmBus.log(TAG, "ERR console socket never appeared")
        return false
    }

    /**
     * One-time-per-boot setup over the serial console: mount /sdcard so site
     * paths work exactly as they do under PRoot, then start the socat bridge.
     */
    private fun setupBridge(): Boolean {
        repeat(6) { attempt ->
            val session = runCatching { SerialSession(Podroid.terminal) }.getOrElse {
                VmBus.log(TAG, "ERR console: ${it.message}")
                Thread.sleep(3000)
                return@repeat
            }
            val ok = session.use { serial ->
                if (!serial.login()) return@use false
                if (attempt == 0) VmBus.log(TAG, "guest shell reached")
                val b64 = android.util.Base64.encodeToString(PS_EXEC.toByteArray(), android.util.Base64.NO_WRAP)
                val setup = buildString {
                    append("mkdir -p /sdcard; ")
                    append("mountpoint -q /sdcard || mount -t hostfs hostfs /sdcard 2>/dev/null; ")
                    // Site roots and container volumes are written for PRoot, where
                    // the card lives at /storage/emulated/0. The guest only had
                    // /sdcard, so those paths did not exist and a site started in
                    // the VM served nothing. Mount it at the same place too, so
                    // both userlands address files the same way.
                    append("mkdir -p /storage/emulated/0; ")
                    append("mountpoint -q /storage/emulated/0 || ")
                    append("mount -t hostfs hostfs /storage/emulated/0 2>/dev/null; ")
                    append("command -v socat >/dev/null 2>&1 || apk add --no-cache socat >/dev/null 2>&1; ")
                    append("echo $b64 | base64 -d > /usr/local/bin/ps-exec; ")
                    append("chmod +x /usr/local/bin/ps-exec; ")
                    append("pkill -f 'TCP-LISTEN:${Podroid.BRIDGE_PORT}' 2>/dev/null; ")
                    append("setsid socat TCP-LISTEN:${Podroid.BRIDGE_PORT},reuseaddr,fork ")
                    append("EXEC:/usr/local/bin/ps-exec,pty,raw,echo=0 >/tmp/ps-socat.log 2>&1 & ")
                    append("echo BRIDGE_SETUP_OK")
                }
                val out = serial.run(setup, 120_000)
                if (!out.contains("BRIDGE_SETUP_OK")) {
                    VmBus.log(TAG, "ERR bridge setup: ${out.takeLast(200).trim()}")
                    return@use false
                }
                VmBus.log(TAG, "command bridge started")
                true
            }
            if (ok) return true
            Thread.sleep(3000)
        }
        VmBus.log(TAG, "ERR could not set up the guest bridge")
        return false
    }

    private fun waitForBridge(): Boolean {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val code = runCatching { Podroid.rawExec("echo BRIDGE_READY", 15_000).first }.getOrDefault(-1)
            if (code == 0) {
                EngineState.podroidRunning.value = true
                VmBus.state(running = true, busy = false, label = "")
                VmBus.log(TAG, "VM ready — sites can run inside Podroid")
                return true
            }
            Thread.sleep(2000)
        }
        VmBus.log(TAG, "ERR command bridge never answered")
        return false
    }

    fun stop() {
        val pid = hostPid
        val p = process
        process = null
        hostPid = 0
        hostLaunch = false
        EngineState.podroidRunning.value = false
        Podroid.clearForwards()
        // A stale pid file is how a reboot ends up adopting the kernel it is
        // still shutting down, so it goes as soon as a stop is decided.
        runCatching { File(Podroid.dir, "vm.pid").delete() }
        if (pid > 0) {
            // The supervisor owns the kernel and the QEMU router; a TERM to it
            // usually takes the group down, and whatever is left is killed by
            // pid. Both lists come from the shell: the VM runs in another
            // SELinux domain, so the app's own /proc scan can come up empty.
            val pkg = Podroid.appContext()?.packageName
            if (pkg != null) {
                VmBus.log(TAG, "stopping VM (pid $pid)")
                Shell.shizukuExec("run-as $pkg sh -c 'kill -TERM $pid 2>/dev/null'")
                Thread.sleep(2_500)
                val left = hostVmPids()
                if (left.isNotEmpty()) {
                    Shell.shizukuExec("run-as $pkg sh -c 'kill -KILL ${left.joinToString(" ")} 2>/dev/null'")
                }
                VmBus.log(TAG, "VM stopped")
            }
            return
        }
        if (p == null) return
        VmBus.log(TAG, "stopping VM")
        runCatching { p.destroy() }
        if (!runCatching { p.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)) {
            runCatching { p.destroyForcibly() }
        }
        VmBus.log(TAG, "VM stopped")
    }
    private val PS_EXEC = """
        #!/bin/sh
        read -r line
        [ -z "${'$'}line" ] && exit 2
        /bin/sh -c "${'$'}line" 2>&1
        printf '__PS_RC=%s\n' "${'$'}?"
    """.trimIndent()

    /** Thin wrapper over the guest's ttyS0, used only to install the bridge. */
    private class SerialSession(path: File) : AutoCloseable {

        private val socket = LocalSocket().apply {
            connect(LocalSocketAddress(path.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        }
        private val output = socket.outputStream
        private val buffer = StringBuilder()

        fun login(): Boolean {
            send("")
            val banner = readUntil(6_000) {
                it.contains("login:") || it.trimEnd().endsWith("#") || it.trimEnd().endsWith("\$")
            }
            if (!banner.contains("login:")) {
                // The tty keeps its session across reconnects, so a prompt from
                // an earlier boot is normal and needs no credentials.
                val tail = banner.trimEnd()
                return tail.endsWith("#") || tail.endsWith("\$")
            }
            send("root")
            readUntil(6_000) { it.contains("Password:", ignoreCase = true) }
            send("podroid")
            val prompt = readUntil(10_000) {
                val t = it.trimEnd()
                t.endsWith("#") || t.endsWith("\$")
            }
            return prompt.trimEnd().let { it.endsWith("#") || it.endsWith("\$") }
        }

        fun run(command: String, timeoutMs: Long): String {
            val marker = "__PS_SER_%d__".format(System.nanoTime())
            buffer.setLength(0)
            send("$command; echo $marker")
            return readUntil(timeoutMs) { it.contains(marker) }
        }

        private fun send(line: String) {
            output.write((line + "\r\n").toByteArray())
            output.flush()
        }

        /** Reads until [done] matches what has arrived; the tty sends it raw. */
        private fun readUntil(timeoutMs: Long, done: (String) -> Boolean): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            socket.soTimeout = 1000
            while (System.currentTimeMillis() < deadline) {
                try {
                    val n = socket.inputStream.available()
                    if (n > 0) {
                        val chunk = ByteArray(n)
                        val read = socket.inputStream.read(chunk, 0, n)
                        if (read <= 0) break
                        buffer.append(String(chunk, 0, read))
                        val clean = stripAnsi(buffer.toString())
                        if (done(clean)) return clean
                    } else {
                        Thread.sleep(50)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // keep waiting until the deadline
                } catch (_: Throwable) {
                    break
                }
            }
            return stripAnsi(buffer.toString())
        }

        private fun stripAnsi(s: String): String =
            s.replace(Regex("\u001B\\[[0-9;?]*[a-zA-Z]"), "")

        override fun close() {
            runCatching { socket.close() }
        }
    }
}
