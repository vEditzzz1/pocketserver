package dev.pocketserver.engine

import android.content.Context
import kotlin.concurrent.thread

/**
 * One-tap package sets, mapped to apk or apt depending on the active distro.
 *
 * Every stack records itself in [Prefs.stacks] when it installs successfully.
 * That record is what lets [heal] put the packages back after the guest
 * filesystem was rebuilt or a package went missing.
 */
object Stacks {

    /**
     * @param probe guest shell test that exits 0 when the stack is usable.
     * @param post guest shell line run after the packages, for stacks whose
     *   tool is not distributed as a distro package (udocker).
     */
    data class Stack(
        val id: String,
        val label: String,
        val apk: List<String>,
        val apt: List<String>,
        val probe: String,
        val post: String? = null
    )

    val all = listOf(
        Stack(
            "static", "Static server", listOf("busybox-extras"), listOf("busybox-static"),
            // Alpine leaves httpd to busybox-extras (its own binary), Ubuntu's
            // busybox-static has the applet but no symlink — both cover this.
            "command -v httpd || command -v nginx || busybox --list 2>/dev/null | grep -qx httpd"
        ),
        Stack(
            "php", "PHP + nginx", listOf("php84", "php84-fpm", "nginx"), listOf("php", "php-fpm", "nginx"),
            "command -v php"
        ),
        Stack(
            "node", "Node.js", listOf("nodejs", "npm"), listOf("nodejs", "npm"),
            "command -v node"
        ),
        Stack(
            "python", "Python 3", listOf("python3", "py3-pip"), listOf("python3", "python3-pip"),
            "command -v python3"
        ),
        Stack(
            "db", "MariaDB", listOf("mariadb", "mariadb-client"), listOf("mariadb-server", "mariadb-client"),
            "command -v mariadbd || command -v mysqld"
        ),
        Stack(
            "tools", "Git + tools", listOf("git", "curl", "unzip"), listOf("git", "curl", "unzip"),
            "command -v git && command -v curl"
        ),
        Stack(
            "udocker", "udocker",
            // udocker assumes a GNU userland: busybox tar lacks --one-file-system
            // and busybox find lacks -gid, which breaks `udocker create`.
            listOf("python3", "curl", "tar", "findutils"),
            listOf("python3", "curl", "tar", "findutils"),
            "command -v udocker >/dev/null 2>&1 && udocker --version >/dev/null 2>&1",
            post = udockerPost()
        )
    )

    /**
     * udocker is not packaged by Alpine or Ubuntu: it is a Python program that
     * has to be fetched from its GitHub release. The tarball keeps the install
     * independent of pip and PEP 668; pip stays as the fallback.
     *
     * The tarball's launcher finds its own package through argv[0], so it must
     * NOT be symlinked into /usr/local/bin (that breaks the import); a tiny
     * wrapper that runs python3 on the real path is what gets installed.
     */
    private fun udockerPost(): String = """
        V=$UDOCKER_VERSION; dir=/opt/udocker-${'$'}V
        if [ ! -x "${'$'}dir/udocker/udocker" ]; then
          mkdir -p /opt
          curl -fsSL "https://github.com/indigo-dc/udocker/releases/download/${'$'}V/udocker-${'$'}V.tar.gz" -o /tmp/udocker.tar.gz && tar -xzf /tmp/udocker.tar.gz -C /opt
          rm -f /tmp/udocker.tar.gz
        fi
        if [ -x "${'$'}dir/udocker/udocker" ]; then
          mkdir -p /usr/local/bin
          rm -f /usr/local/bin/udocker
          printf '#!/bin/sh\nexec python3 %s --allow-root "${'$'}@"\n' "${'$'}dir/udocker/udocker" > /usr/local/bin/udocker
          chmod +x /usr/local/bin/udocker
        fi
        command -v udocker >/dev/null 2>&1 || python3 -m pip install --no-cache-dir udocker
        command -v udocker >/dev/null 2>&1 || python3 -m pip install --no-cache-dir --break-system-packages udocker
        udocker --version 2>&1 | head -1
    """.trimIndent()

    private const val UDOCKER_VERSION = "1.3.17"

    fun byId(id: String): Stack? = all.firstOrNull { it.id == id }

    /** apk/apt cannot run twice at once; package work is serialised. */
    private val pkgLock = java.util.concurrent.locks.ReentrantLock()

    private inline fun <T> locked(block: () -> T): T {
        pkgLock.lock()
        return try {
            block()
        } finally {
            pkgLock.unlock()
        }
    }

    /** Package names the selected distro is asked for (logs, panel). */
    fun packages(stack: Stack): List<String> =
        if (Prefs.distro == Distros.UBUNTU) stack.apt else stack.apk

    /**
     * Install command that asks the guest it actually lands in. The selected
     * distro and the guest can differ (the Podroid VM is Alpine even when Ubuntu
     * is selected), and asking one for the other's package manager is how a
     * package set failed silently.
     */
    fun installCommand(stacks: List<Stack>): String {
        val apk = stacks.flatMap { it.apk }.distinct().joinToString(" ")
        val apt = stacks.flatMap { it.apt }.distinct().joinToString(" ")
        return "if command -v apt-get >/dev/null 2>&1; then " +
            "DEBIAN_FRONTEND=noninteractive apt-get update && " +
            "DEBIAN_FRONTEND=noninteractive apt-get install -y $apt; " +
            "else apk add --no-cache $apk; fi"
    }

    /** Install arbitrary package names with whichever manager the guest has. */
    fun installNamesCommand(names: List<String>): String {
        val list = names.joinToString(" ")
        return "if command -v apt-get >/dev/null 2>&1; then " +
            "DEBIAN_FRONTEND=noninteractive apt-get update && " +
            "DEBIAN_FRONTEND=noninteractive apt-get install -y $list; " +
            "else apk add --no-cache $list; fi"
    }

    /** Package command that takes a stack's packages away again. */
    fun removeCommand(stacks: List<Stack>): String {
        val apk = stacks.flatMap { it.apk }.distinct().joinToString(" ")
        val apt = stacks.flatMap { it.apt }.distinct().joinToString(" ")
        return "if command -v apt-get >/dev/null 2>&1; then " +
            "DEBIAN_FRONTEND=noninteractive apt-get purge -y $apt && apt-get autoremove -y; " +
            "else apk del $apk; fi"
    }

    // ------------------------------------------------------------- status

    /** True when every binary of [stack] answers inside the guest. */
    fun isInstalled(stack: Stack): Boolean {
        val out = Shell.capture("probe", "(${stack.probe}) >/dev/null 2>&1 && echo present", 60)
        return out.contains("present")
    }

    /** True when a static file server exists in the guest. */
    fun hasStaticServer(): Boolean = byId("static")?.let { isInstalled(it) } ?: false

    fun installedIds(): List<String> = all.filter { isInstalled(it) }.map { it.id }

    /**
     * Every probe in one guest shell, so the panel's "check" costs one PRoot
     * start instead of one per package set.
     */
    fun probeAll(): Set<String> {
        if (!EngineState.runtimeInstalled.value) return emptySet()
        val script = all.joinToString("; ") { stack -> "{ ${stack.probe}; } >/dev/null 2>&1 && echo ${stack.id}" }
        val out = Shell.capture("probe", script, 180)
        return out.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /** What a site kind needs, and which sites currently depend on it. */
    fun sitesUsing(stack: Stack): List<String> =
        Servers.profiles.filter { stackFor(it.kind)?.id == stack.id }.map { it.name }

    // ------------------------------------------------------------ install

    fun install(ctx: Context, stack: Stack) {
        if (EngineState.busy.value) {
            EngineState.log("pkg", "busy: ${EngineState.busyLabel.value}")
            return
        }
        thread(name = "pkg-${stack.id}") {
            EngineState.busy.value = true
            EngineState.busyLabel.value = "Installing ${stack.label}"
            try {
                apply("pkg", stack)
                versions()
            } catch (t: Throwable) {
                EngineState.log("pkg", "ERR ${t.message}")
            } finally {
                EngineState.busy.value = false
                EngineState.busyLabel.value = ""
            }
        }
    }

    /**
     * Removes a package set and forgets it. Destructive for packages, not for
     * data: a site whose runtime disappears reinstalls it on its next start
     * ([ensureForKind]), which is why the panel asks twice before doing this.
     */
    fun uninstall(ctx: Context, stack: Stack) {
        if (EngineState.busy.value) {
            EngineState.log("pkg", "busy: ${EngineState.busyLabel.value}")
            return
        }
        thread(name = "pkg-rm-${stack.id}") {
            EngineState.busy.value = true
            EngineState.busyLabel.value = "Removing ${stack.label}"
            try {
                val gone = locked {
                        val names = packages(stack)
                        EngineState.log("pkg", "removing ${names.joinToString(" ")}")
                        val code = Shell.run("pkg", removeCommand(listOf(stack)), timeoutSec = 900)
                    // udocker's own files are not owned by apk/apt.
                    if (stack.id == "udocker") {
                        Shell.run(
                            "pkg",
                            "rm -rf /opt/udocker-* /usr/local/bin/udocker /tmp/udocker.tar.gz",
                            timeoutSec = 120
                        )
                    }
                    val ok = !isInstalled(stack)
                    if (ok) {
                        Prefs.forgetStack(stack.id)
                        EngineState.log("pkg", "${stack.label} removed")
                    } else {
                        EngineState.log("pkg", "ERR ${stack.label} is still present (exit $code)")
                    }
                    ok
                }
                if (gone) versions()
            } catch (t: Throwable) {
                EngineState.log("pkg", "ERR ${t.message}")
            } finally {
                EngineState.busy.value = false
                EngineState.busyLabel.value = ""
            }
        }
    }

    /**
     * Installs [stack] if it is missing, synchronously. Used when a site is
     * started, so the command the site needs is actually there.
     */
    fun ensure(tag: String, stack: Stack): Boolean {
        if (isInstalled(stack)) {
            Prefs.rememberStack(stack.id)
            return true
        }
        EngineState.log(tag, "${stack.label} missing — installing ${packages(stack).joinToString(" ")}")
        return apply(tag, stack)
    }

    fun ensureForKind(tag: String, kind: ServerKind): Boolean =
        stackFor(kind)?.let { ensure(tag, it) } ?: true

    fun stackFor(kind: ServerKind): Stack? = when (kind) {
        ServerKind.STATIC -> byId("static")
        ServerKind.PHP -> byId("php")
        ServerKind.NODE -> byId("node")
        ServerKind.PYTHON -> byId("python")
        ServerKind.CONTAINER -> byId("udocker")
        ServerKind.CUSTOM -> null
    }

    /** Runs the package command and remembers the stack when it worked. */
    private fun apply(tag: String, stack: Stack): Boolean = locked {
        var code = -1
        var attempt = 0
        while (attempt < 3) {
            attempt++
            if (attempt > 1) {
                EngineState.log(tag, "retrying (${attempt}/3) — refreshing DNS")
                RuntimeInstaller.refreshDns()
                Thread.sleep(4_000)
            }
            code = Shell.run(tag, installCommand(listOf(stack)), timeoutSec = 900)
            if (code == 0 || isInstalled(stack)) break
        }
        // The post-install step needs the packages (udocker's wrapper runs
        // python3/curl), so it only runs when apk/apt succeeded.
        if (code == 0) {
            stack.post?.let { post ->
                EngineState.log(tag, "configuring ${stack.label}")
                Shell.run(tag, post, timeoutSec = 600)
            }
        }
        val ok = isInstalled(stack)
        if (ok) {
            Prefs.rememberStack(stack.id)
            EngineState.log(tag, "${stack.label} ready")
            if (stack.id == "udocker") afterUdocker(tag)
        } else {
            EngineState.log(
                tag,
                if (code != 0) {
                    "ERR ${stack.label} could not be installed (exit $code) — " +
                        "the guest could not reach the mirror. Check the connection and try again."
                } else {
                    "ERR ${stack.label} installed, but its check still fails: ${stack.probe}"
                }
            )
        }
        ok
    }

    /**
     * udocker only needs to exist for `pull`/`create`; the image is executed by
     * PocketServer's own PRoot, so udocker's engine download is not required.
     */
    private fun afterUdocker(tag: String) {
        EngineState.log(tag, containersHint())
    }

    // ---------------------------------------------------------- self-heal

    /**
     * Reinstalls the recorded package sets that are no longer present. Runs
     * after every runtime install/repair/reset, so a rebuilt guest filesystem
     * comes back with the packages the user had.
     */
    fun heal(ctx: Context) {
        if (!EngineState.runtimeInstalled.value) return
        locked {
            val recorded = Prefs.stacks
            if (recorded.isEmpty()) {
                EngineState.log("heal", "no package sets recorded yet")
                return@locked
            }
            val missing = all.filter { it.id in recorded && !isInstalled(it) }
            if (missing.isEmpty()) {
                EngineState.log("heal", "all ${recorded.size} recorded package set(s) present")
                return@locked
            }
            EngineState.log("heal", "restoring ${missing.joinToString(", ") { it.label }}")
            // The guest may have been rebuilt on a different network.
            RuntimeInstaller.refreshDns()
            // Plain package sets can share one apk/apt run; stacks with a
            // post-install step need their own pass.
            val plain = missing.filter { it.post == null }
            if (plain.isNotEmpty()) {
                val code = Shell.run("heal", installCommand(plain), timeoutSec = 900)
                val restored = plain.filter { isInstalled(it) }
                restored.forEach { Prefs.rememberStack(it.id) }
                EngineState.log(
                    "heal",
                    "${restored.size}/${plain.size} package set(s) restored (exit $code)"
                )
            }
            missing.filter { it.post != null }.forEach { apply("heal", it) }
        }
    }

    fun versions() {
        Shell.run(
            "versions",
            "for b in php node npm python3 nginx mariadb udocker git; do " +
                "p=$(command -v \$b) && echo \"\$b: \$(\$b --version 2>&1 | head -1)\"; done",
            timeoutSec = 120
        )
    }

    /**
     * Containers work in every mode now: the image is entered by a single PRoot
     * on the app UID, so no root is involved. What the kernel cannot give is
     * isolation and limits.
     */
    fun containersHint(): String =
        "containers run with one PRoot on the app UID (no PID isolation, no cgroups, no SysV IPC)"
}
