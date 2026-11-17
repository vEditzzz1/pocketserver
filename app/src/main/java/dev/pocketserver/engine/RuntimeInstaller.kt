package dev.pocketserver.engine

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import java.io.File
import kotlin.concurrent.thread

/**
 * First-run setup: unpacks the PRoot runtime and an Alpine rootfs into the
 * app's private directory, then proves that a shell can be entered.
 *
 * Installing is *repairing*: an existing guest filesystem is never thrown
 * away, because it holds everything installed with apk/apt. Only [reset]
 * rebuilds it, and even then [Stacks.heal] restores the package sets that were
 * installed before.
 */
object RuntimeInstaller {

    const val ALPINE_BRANCH = "v3.24"
    val ALPINE_TARBALL = "rootfs/alpine-3.24.4-aarch64.targz"

    /** Installs the runtime, or repairs it in place when it already exists. */
    fun install(context: Context) = start(context, reset = false)

    /**
     * Rebuilds the guest filesystem from the APK. Only called from an explicit
     * user action; sites and package sets are restored afterwards.
     */
    fun reset(context: Context) = start(context, reset = true)

    private fun start(context: Context, reset: Boolean) {
        if (EngineState.busy.value) {
            EngineState.log("setup", "busy: ${EngineState.busyLabel.value}")
            return
        }
        val app = context.applicationContext
        thread(name = "runtime-install") { doInstall(app, reset) }
    }

    /** Runs a shell line inside the userland on a background thread. */
    fun runShell(context: Context, command: String, tag: String = "shell") {
        thread(name = "guest-$tag") {
            if (EngineState.busy.value) {
                EngineState.log(tag, "busy: ${EngineState.busyLabel.value}")
                return@thread
            }
            EngineState.busy.value = true
            EngineState.busyLabel.value = "Running command"
            try {
                Exec.run(tag, Paths.shellArgs(command), Paths.env(), timeoutSec = 180)
            } catch (t: Throwable) {
                EngineState.log(tag, "ERR ${t.message}")
            } finally {
                EngineState.busy.value = false
                EngineState.busyLabel.value = ""
            }
        }
    }

    private fun doInstall(ctx: Context, reset: Boolean) {
        EngineState.busy.value = true
        EngineState.busyLabel.value = when {
            reset -> "Resetting runtime"
            Paths.installed() -> "Repairing runtime"
            else -> "Installing runtime"
        }
        val startedAt = System.currentTimeMillis()
        try {
            assets = ctx.assets
            EngineState.log("setup", "runtime directory ${Paths.files.absolutePath}")
            if (reset) {
                Servers.stopAll()
                EngineState.log(
                    "setup",
                    "reset: rebuilding the guest filesystem (${Prefs.stacks.size} package set(s) will be restored)"
                )
                Paths.rootfs.deleteRecursively()
            }
            copyRuntime()
            if (Prefs.distro == Distros.UBUNTU) {
                repairRootfs()
                EngineState.log("setup", "Ubuntu is the active distro; keeping its rootfs")
            } else {
                ensureRootfs()
            }
            Distros.writeConfig(ctx)
            writeResolvConf(ctx)
            writeCerts()
            Paths.marker.writeText("proot 5.1.107.102 / ${Prefs.distro} $ALPINE_BRANCH\n")
            verify()
            EngineState.runtimeInstalled.value = Paths.installed()
            val seconds = (System.currentTimeMillis() - startedAt) / 1000
            EngineState.log("setup", "runtime ${if (Paths.installed()) "ready" else "incomplete"} in ${seconds}s")
            Stacks.heal(ctx)
        } catch (t: Throwable) {
            EngineState.log("setup", "ERR install failed: ${t.message}")
            EngineState.runtimeInstalled.value = Paths.installed()
        } finally {
            EngineState.busy.value = false
            EngineState.busyLabel.value = ""
        }
    }

    /**
     * Unpacks the PRoot pieces, skipping whatever is already in place. The
     * binaries are only rewritten when they are missing or empty, so a repair
     * never disturbs a working runtime.
     */
    private fun copyRuntime() {
        var repaired = 0
        for (name in listOf("proot", "loader")) {
            val out = File(Paths.runtime, name)
            if (restore(name, out)) repaired++
            Os.chmod(out.absolutePath, 0b111_101_101) // 0755
        }
        for (name in listOf("libtalloc.so.2", "libandroid-shmem.so")) {
            val out = File(Paths.lib, name)
            if (restore(name, out)) repaired++
            Os.chmod(out.absolutePath, 0b110_100_100) // 0644
        }
        if (restore("cacert.pem", File(Paths.runtime, "cacert.pem"))) repaired++
        EngineState.log(
            "setup",
            if (repaired == 0) "proot runtime intact" else "proot runtime repaired ($repaired file(s))"
        )
    }

    /** Writes one asset only when the target is missing; true when it did. */
    private fun restore(name: String, out: File): Boolean {
        val present = out.exists() && out.length() > 0
        if (present) return false
        ctxAssets(name, out)
        return true
    }

    /** Alpine's minirootfs ships no CA store; without it every HTTPS apk call fails. */
    private fun writeCerts() {
        val bundle = File(Paths.runtime, "cacert.pem")
        if (!bundle.exists()) return
        val target = File(Paths.rootfs, "etc/ssl/certs/ca-certificates.crt")
        // Ubuntu ships its own (complete) bundle; never replace it.
        if (target.exists() && target.length() > 0) return
        target.parentFile?.mkdirs()
        bundle.copyTo(target, overwrite = true)
        Os.chmod(target.absolutePath, 0b110_100_100)
        EngineState.log("setup", "CA bundle installed (${bundle.length() / 1024} kB)")
    }

    private fun ctxAssets(name: String, out: File) {
        val asset = "runtime/$name"
        out.parentFile?.mkdirs()
        assets.open(asset).use { input -> out.outputStream().use { output -> input.copyTo(output) } }
    }

    // Assets need a Context; kept as a field to avoid threading it everywhere.
    private lateinit var assets: android.content.res.AssetManager

    private fun ensureRootfs() {
        // /bin/sh is an absolute symlink to /bin/busybox inside the guest, which
        // File.exists() would resolve against Android's real root. busybox is a
        // regular file and is the reliable marker.
        val marker = File(Paths.rootfs, "bin/busybox")
        if (marker.exists()) {
            EngineState.log("setup", "guest filesystem present — repairing in place")
            return
        }
        val partial = Paths.rootfs.list()?.isNotEmpty() == true
        EngineState.log(
            "setup",
            if (partial) "guest filesystem incomplete — restoring missing base files"
            else "unpacking Alpine $ALPINE_BRANCH (${ALPINE_TARBALL.substringAfterLast('/')})"
        )
        Paths.rootfs.mkdirs()
        val staged = File(Paths.tmp, "alpine.tar.gz")
        assets.open(ALPINE_TARBALL).use { input ->
            staged.outputStream().buffered(65536).use { output -> input.copyTo(output) }
        }
        val started = System.currentTimeMillis()
        val entries = TarGz.extract(staged, Paths.rootfs)
        staged.delete()
        EngineState.log("setup", "extracted $entries entries in ${(System.currentTimeMillis() - started)}ms")
        if (!marker.exists()) {
            throw IllegalStateException("rootfs extraction incomplete: /bin/busybox missing")
        }
    }

    /** Ubuntu rootfs: nothing to extract, but a broken one has to be noticed. */
    private fun repairRootfs() {
        val marker = File(Paths.rootfs, "bin/bash")
        if (marker.exists()) return
        EngineState.log("setup", "ERR Ubuntu rootfs is missing /bin/bash — reinstall Ubuntu from the panel")
        throw IllegalStateException("Ubuntu rootfs incomplete")
    }

    /**
     * Re-writes the guest resolver config from the phone's current DNS.
     *
     * Android's DNS changes when the phone moves between Wi-Fi and mobile data,
     * so a guest set up before the network was up fails with
     * "DNS: transient error (try again later)". Package installs call this
     * before every attempt.
     */
    fun refreshDns(): Boolean {
        val ctx = Prefs.context() ?: return false
        writeResolvConf(ctx)
        return true
    }

    private fun writeResolvConf(ctx: Context) {
        val dns = suggestedDns(ctx)
        val lines = dns.distinct().joinToString("\n") { "nameserver $it" }
        val file = File(Paths.rootfs, "etc/resolv.conf")
        file.parentFile?.mkdirs()
        file.writeText(lines + "\n")
        EngineState.log("setup", "resolv.conf: ${dns.joinToString(", ")}")
    }

    private fun verify() {
        val ubuntu = Prefs.distro == Distros.UBUNTU
        EngineState.log("setup", if (ubuntu) "verifying PRoot + Ubuntu…" else "verifying PRoot + Alpine…")
        val probe = if (ubuntu) "uname -m; cat /etc/os-release | head -1; bash --version | head -1"
        else "uname -m; cat /etc/alpine-release; apk --version | head -1"
        val code = Exec.run("verify", Paths.shellArgs(probe), Paths.env(), timeoutSec = 120)
        if (code != 0) throw IllegalStateException("runtime verification failed (exit $code)")
    }

    fun removeRuntime() {
        EngineState.runtimeInstalled.value = false
        Paths.rootfs.deleteRecursively()
        Paths.marker.delete()
        EngineState.log("setup", "rootfs removed")
    }

    /**
     * The device's resolvers first, then public ones. Link-local IPv6 resolvers
     * (`fe80::…%wlan0`) cannot be used inside PRoot, and IPv4 answers are what
     * musl tries first, so both are ordered that way.
     */
    fun suggestedDns(ctx: Context): List<String> {
        val usable = androidDns(ctx)
            .filter {
                it.isNotBlank() &&
                    !it.contains('%') &&
                    !it.startsWith("fe80", ignoreCase = true)
            }
            .sortedBy { if (it.contains(':')) 1 else 0 }
        return (usable + listOf("1.1.1.1", "8.8.8.8")).distinct().take(3)
    }

    /** [suggestedDns] for callers that do not hold a Context (engine singletons). */
    fun suggestedDnsOrEmpty(): List<String> =
        Prefs.context()?.let { runCatching { suggestedDns(it) }.getOrDefault(emptyList()) }
            ?: emptyList()

    private fun androidDns(ctx: Context): List<String> = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val servers = ArrayList<String>()
        val active = cm.activeNetwork
        cm.allNetworks.forEach { network ->
            val lp = cm.getLinkProperties(network) ?: return@forEach
            val usable = network == active || cm.getNetworkCapabilities(network)
                ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            if (usable) servers.addAll(lp.dnsServers.mapNotNull { it.hostAddress })
        }
        servers
    } catch (_: Throwable) {
        emptyList()
    }
}
