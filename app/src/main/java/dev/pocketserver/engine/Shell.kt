package dev.pocketserver.engine

import android.content.Context
import android.content.pm.PackageManager
import android.system.Os
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Every way PocketServer can enter the Linux userland.
 *
 *  - PRoot   : proot as the app UID (works everywhere, no privileges)
 *  - ROOT    : real chroot through `su` (fastest, can bind port 80/443)
 *  - SHIZUKU : the same PRoot runtime executed by the Shizuku service, so the
 *              sites keep running when the app process is killed
 *  - PODROID : a real Alpine kernel in the `:vm` process; on a phone whose
 *              seccomp filter kills that kernel this falls back to PRoot and
 *              says why once per app process
 */
object Shell {

    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val SHIZUKU_REQ = 4242
    private const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** Set the first time the Podroid VM fails here, so the console says it once. */
    @Volatile private var podroidFallbackLogged = false

    /** Set the first time a command is routed into an already-running VM. */
    @Volatile private var vmRoutingLogged = false

    // ---------------------------------------------------------------- caps

    fun rootBinary(): String? {
        for (path in listOf(
            "/system_ext/bin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/debug_ramdisk/su"
        )) {
            if (File(path).canExecute()) return path
        }
        return null
    }

    fun hasRoot(): Boolean {
        val su = rootBinary() ?: return false
        return try {
            val process = ProcessBuilder(su, "-c", "id").redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor(8, TimeUnit.SECONDS)
            out.contains("uid=0(root)")
        } catch (_: Throwable) {
            false
        }
    }

    fun shizukuInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
        true
    } catch (_: Throwable) {
        false
    }

    fun shizukuAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun shizukuGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun requestShizuku() {
        try {
            Shizuku.requestPermission(SHIZUKU_REQ)
        } catch (_: Throwable) {
        }
    }

    /**
     * UID the Shizuku shell really runs as, or null while Shizuku is unusable.
     *
     * Shizuku started from a root shell or through `adb root` runs its own
     * server as UID 0, so commands it forks have real root: the guest can bind
     * ports under 1024 even though the mode is not Root. Cached — one extra
     * command per app process, and no caching at all while Shizuku is down so a
     * later connection is still noticed.
     */
    @Volatile private var shizukuUidCache: Int? = null

    fun shizukuUid(): Int? {
        shizukuUidCache?.let { return it }
        val usable = runCatching { shizukuAlive() && shizukuGranted() }.getOrDefault(false)
        if (!usable) return null
        val uid = runCatching {
            val (_, output) = shizukuExec("id -u")
            output.lineSequence()
                .mapNotNull { it.trim().toIntOrNull() }
                .firstOrNull()
        }.getOrNull()
        if (uid != null) shizukuUidCache = uid
        return uid
    }

    // ------------------------------------------------------------- helpers

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Starts [command] inside the guest with the currently selected mode. */
    fun start(tag: String, command: String): Process? = startInRootfs(tag, Paths.rootfs, command)

    /**
     * Starts [command] inside any rootfs: the active distro, or an unpacked
     * container image. PRoot, root/chroot and Shizuku are all supported; a
     * container never nests PRoot, it gets its own single PRoot here.
     */
    fun startInRootfs(
        tag: String,
        target: File,
        command: String,
        binds: List<Pair<String, String>> = emptyList(),
        workdir: String = "/root",
        identity: String = "0:0"
    ): Process? {
        return try {
            val mode = EngineState.mode.value
            // A running VM owns every forwarded site port (QEMU binds them on the
            // host), so a second userland on the same ports cannot bind — a PRoot
            // httpd just dies with "Address in use" while the panel says "ok".
            // While the kernel is up, guest commands therefore go into the guest,
            // whichever mode is selected.
            if (mode != EngineState.Mode.Podroid && routesToVm()) {
                if (!vmRoutingLogged) {
                    vmRoutingLogged = true
                    EngineState.log(
                        "mode",
                        "Podroid VM is running — running guest commands inside it " +
                            "(${mode.name} would fight it for the site ports)"
                    )
                }
                startPodroid(command, target, binds, workdir, identity)
            } else {
                when (mode) {
                    EngineState.Mode.PRoot -> startProot(command, target, binds, workdir, identity)
                    EngineState.Mode.Root -> startRoot(command, target, binds, workdir)
                    EngineState.Mode.Shizuku -> startShizuku(command, target, binds, workdir, identity)
                    EngineState.Mode.Podroid -> startPodroid(command, target, binds, workdir, identity)
                }
            }
        } catch (t: Throwable) {
            EngineState.log(tag, "ERR cannot start: ${t.message}")
            null
        }
    }

    private fun startProot(
        command: String,
        target: File = Paths.rootfs,
        binds: List<Pair<String, String>> = emptyList(),
        workdir: String = "/root",
        identity: String = "0:0"
    ): Process {
        val cmd = Paths.prootArgsFor(target, workdir, binds, identity) + listOf("/bin/sh", "-c", command)
        val builder = ProcessBuilder(cmd)
        builder.redirectErrorStream(true)
        builder.environment().putAll(Paths.env())
        return builder.start()
    }

    /**
     * Real chroot. Volumes are honoured here too (bind mounts), otherwise a
     * container's configuration would silently do nothing in Root mode.
     */
    private fun startRoot(
        command: String,
        target: File = Paths.rootfs,
        binds: List<Pair<String, String>> = emptyList(),
        workdir: String = "/root"
    ): Process {
        val su = rootBinary() ?: throw IllegalStateException("no su binary found")
        val cd = if (workdir.isBlank() || workdir == "/") "" else "cd ${q(workdir)} || exit 1; "
        val guest = "export PATH=$GUEST_PATH HOME=/root TERM=xterm; $cd$command"
        val mounts = buildString {
            binds.forEach { (host, guestPath) ->
                if (guestPath.isBlank() || guestPath.contains("..")) return@forEach
                val mountPoint = "\$R${q(guestPath)}"
                append("\nmkdir -p $mountPoint\n")
                append("mountpoint -q $mountPoint || mount --bind ${q(host)} $mountPoint\n")
            }
        }
        val script = """
            R=${target.absolutePath}
            mkdir -p ${'$'}R/dev ${'$'}R/proc ${'$'}R/sys ${'$'}R/sdcard ${'$'}R/tmp$mounts
            mountpoint -q ${'$'}R/proc || mount -t proc proc ${'$'}R/proc
            mountpoint -q ${'$'}R/dev || mount --bind /dev ${'$'}R/dev
            mountpoint -q ${'$'}R/sys || mount --bind /sys ${'$'}R/sys
            mountpoint -q ${'$'}R/sdcard || mount --bind /storage/emulated/0 ${'$'}R/sdcard
            exec chroot ${'$'}R /bin/sh -c ${q(guest)}
        """.trimIndent()
        val builder = ProcessBuilder(su, "-c", script)
        builder.redirectErrorStream(true)
        return builder.start()
    }

    private fun startShizuku(
        command: String,
        target: File = Paths.rootfs,
        binds: List<Pair<String, String>> = emptyList(),
        workdir: String = "/root",
        identity: String = "0:0"
    ): Process {
        if (!shizukuAlive() || !shizukuGranted()) {
            throw IllegalStateException("Shizuku is not running or permission is not granted")
        }
        val prootCmd = Paths.prootArgsFor(target, workdir, binds, identity) + listOf("/bin/sh", "-c", command)
        // Shizuku does not always apply the env array, so the variables are
        // also exported inside the command itself.
        val script = buildString {
            append("export PATH=$GUEST_PATH HOME=/root TERM=xterm ")
            append("PROOT_LOADER=${q(Paths.loader.absolutePath)} ")
            append("PROOT_TMP_DIR=${q(Paths.tmp.absolutePath)} ")
            append("LD_LIBRARY_PATH=${q(Paths.lib.absolutePath)}; ")
            append("exec ")
            append(prootCmd.joinToString(" ") { q(it) })
            append(" 2>&1")
        }
        val env = arrayOf(
            "PROOT_LOADER=${Paths.loader.absolutePath}",
            "PROOT_TMP_DIR=${Paths.tmp.absolutePath}",
            "LD_LIBRARY_PATH=${Paths.lib.absolutePath}",
            "PATH=$GUEST_PATH",
            "HOME=/root",
            "TERM=xterm"
        )
        return shizukuProcess(arrayOf("/system/bin/sh", "-c", script), env, null)
    }

    // ----------------------------------------------------------- shizuku io

    /** Shizuku 13 keeps newProcess() private; the AIDL interface is public. */
    private fun shizukuProcess(cmd: Array<String>, env: Array<String>?, dir: String?): Process {
        val binder = Shizuku.getBinder() ?: throw IllegalStateException("Shizuku binder unavailable")
        val service = IShizukuService.Stub.asInterface(binder)
        val remote: IRemoteProcess = service.newProcess(cmd, env, dir)
        val ctor = ShizukuRemoteProcess::class.java
            .getDeclaredConstructor(IRemoteProcess::class.java)
            .apply { isAccessible = true }
        return ctor.newInstance(remote) as Process
    }

    /**
     * The Shizuku shell cannot read the app's private directory unless it is
     * traversable, so the app relaxes permissions on its own runtime once.
     * Files keep their normal modes; only the two directory levels and the
     * proot scratch dir become group/other accessible.
     */
    fun prepareShizukuAccess(): Boolean {
        return try {
            val dataDir = Paths.files.parentFile
            if (dataDir != null) Os.chmod(dataDir.absolutePath, 0b111_001_001) // 0711
            Os.chmod(Paths.files.absolutePath, 0b111_001_001)
            Os.chmod(Paths.runtime.absolutePath, 0b111_001_001)
            Os.chmod(Paths.tmp.absolutePath, 0b111_111_111) // 0777 scratch
            Os.chmod(Paths.proot.absolutePath, 0b111_101_101)
            Os.chmod(Paths.loader.absolutePath, 0b111_101_101)
            Paths.lib.listFiles()?.forEach { Os.chmod(it.absolutePath, 0b110_100_100) }
            File(Paths.runtime, "cacert.pem").takeIf { it.exists() }
                ?.let { Os.chmod(it.absolutePath, 0b110_100_100) }
            Os.chmod(Paths.rootfs.absolutePath, 0b111_101_101)
            EngineState.log("shizuku", "runtime made reachable for the shell")
            true
        } catch (t: Throwable) {
            EngineState.log("shizuku", "ERR chmod: ${t.message}")
            false
        }
    }

    /** Verifies that the Shizuku shell can actually execute the runtime. */
    fun verifyShizuku(): Boolean {
        return try {
            val (code, output) = shizukuExec("${q(Paths.proot.absolutePath)} --version 2>&1 | head -2")
            EngineState.log("shizuku", "proot check (exit $code): ${output.trim().take(120)}")
            output.contains("PRoot", ignoreCase = true) || output.contains("____")
        } catch (t: Throwable) {
            EngineState.log("shizuku", "ERR verify: ${t.message}")
            false
        }
    }

    // ------------------------------------------------------ privileged shell

    /** Cached: a VM boot must not pay for `su -c id` more than once. */
    @Volatile private var rootGranted: Boolean? = null

    fun hasWorkingRoot(): Boolean {
        rootGranted?.let { return it }
        val ok = hasRoot()
        rootGranted = ok
        return ok
    }

    private fun shizukuUsable(): Boolean =
        runCatching { shizukuAlive() && shizukuGranted() }.getOrDefault(false)

    /**
     * True when the VM can be started at all.
     *
     * It has to be **Shizuku**: the Shizuku server forks the process, so the
     * kernel is not a descendant of the app and does not inherit the zygote
     * seccomp filter. A `su` child of the app inherits it and is killed 7–30 ms
     * after boot, exactly like an in-process kernel (measured), so root alone is
     * not enough here.
     */
    fun canHostVm(): Boolean = shizukuUsable()

    /**
     * True when guest commands run inside the Podroid VM: either it is the
     * selected mode, or it is running and therefore owns the site ports.
     * Ubuntu is excluded — the VM is an Alpine image and cannot be its guest.
     */
    fun routesToVm(): Boolean =
        Prefs.distro != Distros.UBUNTU &&
            (EngineState.mode.value == EngineState.Mode.Podroid || Podroid.running())

    /**
     * Podroid mode runs the command inside the UML-booted VM instead of PRoot.
     * `/sdcard` is mounted there from the real shared storage, so the same site
     * paths work; a container's rootfs lives in the VM's overlay, so [target] and
     * [binds] do not apply.
     *
     * When the VM cannot start (this phone's app seccomp filter stops the guest
     * kernel), the command still runs — in PRoot — and the console says exactly
     * why, instead of failing the site.
     */
    private fun startPodroid(
        command: String,
        target: File = Paths.rootfs,
        binds: List<Pair<String, String>> = emptyList(),
        workdir: String = "/root",
        identity: String = "0:0"
    ): Process {
        return try {
            Podroid.start(command, workdir)
        } catch (t: Throwable) {
            val why = EngineState.podroidError.value.ifBlank {
                t.message ?: "the VM did not start"
            }
            // One site start runs several guest commands (probes, then the
            // server), so explain this once per app process instead of
            // repeating the same paragraph three times in the console.
            if (!podroidFallbackLogged) {
                podroidFallbackLogged = true
                EngineState.log("mode", "Podroid VM unavailable — $why")
                EngineState.log("mode", "running sites in PRoot instead")
            } else {
                EngineState.log("mode", "PRoot (Podroid VM unavailable)")
            }
            startProot(command, target, binds, workdir, identity)
        }
    }

    /** One-shot command through Shizuku, returning output for checks. */
    fun shizukuExec(script: String, timeoutMs: Long = 60_000): Pair<Int, String> {
        val process = shizukuProcess(arrayOf("/system/bin/sh", "-c", script), null, null)
        val builder = StringBuilder()
        val reader = thread(name = "shizuku-check") {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { builder.append(it).append('\n') }
            }
        }
        val code = awaitExit(process, timeoutMs)
        reader.join(2_000)
        return code to builder.toString()
    }

    /** One-shot helper that returns the guest's output, for checks. */
    fun capture(tag: String, command: String, timeoutSec: Long = 60): String {
        val process = start(tag, command) ?: return ""
        val builder = StringBuilder()
        val reader = thread(name = "capture") {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { builder.append(it).append('\n') }
            }
        }
        awaitExit(process, timeoutSec * 1000)
        reader.join(2_000)
        return builder.toString()
    }

    /** One-shot helper that streams the output into the console. */
    fun run(tag: String, command: String, timeoutSec: Long = 0): Int {
        EngineState.log(tag, "$ ${command.take(200)}")
        val process = start(tag, command) ?: return -1
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                if (line.isNotBlank()) EngineState.log(tag, line)
            }
        } catch (_: Throwable) {
        }
        val code = if (timeoutSec > 0) awaitExit(process, timeoutSec * 1000) else {
            try {
                process.waitFor()
            } catch (_: Throwable) {
                -1
            }
        }
        if (code < 0) {
            process.destroyForcibly()
            EngineState.log(tag, "ERR timeout")
        }
        return code
    }

    /**
     * ShizukuRemoteProcess overrides waitFor() only; its timed variant throws.
     * Polling exitValue()/alive() works for local and remote processes alike.
     */
    private fun awaitExit(process: Process, timeoutMs: Long): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                return process.exitValue()
            } catch (_: Throwable) {
                if (alive(process) == false) return 0
                Thread.sleep(50)
            }
        }
        return -1
    }

    /** ShizukuRemoteProcess exposes alive(); plain Process does not. */
    private fun alive(process: Process): Boolean? = runCatching {
        process.javaClass.getMethod("alive").invoke(process) as Boolean
    }.getOrNull()
}
