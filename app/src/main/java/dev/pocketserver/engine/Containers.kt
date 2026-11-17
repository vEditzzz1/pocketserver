package dev.pocketserver.engine

import java.io.File

/**
 * Docker images without Docker.
 *
 * udocker pulls an image from Docker Hub and unpacks it into
 * `$HOME/.udocker/containers/<name>/ROOT` inside the guest. Running it with
 * `udocker run` would nest udocker's PRoot inside ours, which cannot work, so
 * PocketServer enters that directory with a single PRoot of its own: no root,
 * no namespaces, no daemon.
 *
 * The trade-offs are the kernel's, not ours: no PID/IPC isolation, no cgroups,
 * no SysV IPC, no real port mapping, and every syscall goes through ptrace.
 * [ContainerSpec] holds the settings that *can* be honoured here.
 */
object Containers {

    /** udocker's data dir inside the guest; HOME is /root. */
    private fun store(): File = File(Paths.rootfs, "root/.udocker/containers")

    /** Directory that becomes `/` inside the container. */
    fun rootfs(name: String): File = File(store(), "$name/ROOT")

    /** True when the image layers were extracted and look like a rootfs. */
    fun exists(name: String): Boolean {
        val dir = rootfs(name)
        return dir.isDirectory && (File(dir, "etc").isDirectory || File(dir, "bin").isDirectory)
    }

    /** Names udocker created, sorted for a stable list. */
    fun list(): List<String> =
        store().listFiles()
            ?.filter { it.isDirectory && File(it, "ROOT").isDirectory }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    /** Downloads an image without creating a container (panel "Pull" button). */
    fun pull(tag: String, image: String, platform: String = ""): Boolean {
        if (!EngineState.runtimeInstalled.value) {
            EngineState.log(tag, "ERR install the runtime first")
            return false
        }
        if (image.isBlank()) {
            EngineState.log(tag, "ERR no image name")
            return false
        }
        val arch = if (platform.isBlank()) "" else "--platform=${q(platform)} "
        EngineState.log(tag, "pulling $image${if (platform.isBlank()) "" else " ($platform)"}")
        return Shell.run(tag, "udocker pull $arch${q(image)}", timeoutSec = 1800) == 0
    }

    /**
     * Pulls [image] and creates [name] once. Returns true when [rootfs] is a
     * usable image; the caller then starts it with [Shell.startInRootfs].
     */
    fun ensure(tag: String, image: String, name: String, platform: String = ""): Boolean {
        if (!EngineState.runtimeInstalled.value) {
            EngineState.log(tag, "ERR install the runtime first")
            return false
        }
        if (image.isBlank()) {
            EngineState.log(tag, "ERR no image name")
            return false
        }
        if (exists(name)) {
            EngineState.log(tag, "using existing container $name")
            return true
        }
        if (!pull(tag, image, platform)) {
            EngineState.log(tag, "ERR pull failed — install the udocker stack first")
            return false
        }
        EngineState.log(tag, "creating container $name")
        Shell.run(tag, "udocker create --name=${q(name)} ${q(image)}", timeoutSec = 900)
        if (!exists(name)) {
            EngineState.log(tag, "ERR container $name was not created")
            return false
        }
        EngineState.log(tag, "container $name ready")
        return true
    }

    /**
     * Drops a container and unpacks the image again, which is the only way to
     * pick up a different tag. Destructive on purpose: the panel asks twice.
     */
    fun recreate(tag: String, name: String, image: String, platform: String = ""): Boolean {
        EngineState.log(tag, "removing $name to recreate it from $image")
        Shell.run(tag, "udocker rm ${q(name)}", timeoutSec = 300)
        // udocker can leave the directory behind when its index is already
        // gone; ensure() would then reuse the old layers forever.
        val leftover = File(store(), name)
        if (leftover.exists()) runCatching { leftover.deleteRecursively() }
        return ensure(tag, image, name, platform)
    }

    /**
     * Binds for the container: the site folder at /site (so a served site can
     * be edited from the phone) plus every configured volume.
     */
    fun bindsFor(p: ServerProfile): List<Pair<String, String>> {
        val binds = ArrayList<Pair<String, String>>()
        if (p.root.isNotBlank()) binds.add(p.root to "/site")
        p.container.normalized().volumes.forEach { volume ->
            if (volume.guest != "/site") binds.add(volume.host to volume.guest)
        }
        return binds
    }

    /**
     * The `udocker run` line, expressed for a PRoot shell: working directory,
     * environment, then the entrypoint + command. `exec` is used only for a
     * plain command, because it would otherwise skip the rest of the line.
     */
    fun commandFor(p: ServerProfile): String {
        val spec = p.container.normalized()
        val body = p.body.trim()
        val composed = when {
            spec.entrypoint.isBlank() && body.isBlank() -> "cat /etc/os-release"
            spec.entrypoint.isBlank() -> body
            body.isBlank() -> spec.entrypoint
            else -> spec.entrypoint + " " + body
        }
        val lines = ArrayList<String>()
        lines.add("cd " + q(spec.guestWorkdir()) + " || exit 1")
        // There is no port mapping under PRoot: the process binds the phone's
        // port directly, so it has to listen on the site's port. Catalog
        // commands use $PS_PORT for exactly that.
        val exports = ArrayList<String>()
        if (p.port in 1..65535) exports.add("PS_PORT=" + q(p.port.toString()))
        spec.env.forEach { (key, value) -> exports.add("$key=" + q(value)) }
        if (exports.isNotEmpty()) lines.add("export " + exports.joinToString(" "))
        lines.add(if (isPlainCommand(composed)) "exec $composed" else composed)
        return lines.joinToString("; ")
    }

    /**
     * Image metadata plus the on-disk rootfs, for the panel's inspect view.
     * `udocker inspect` reports the image and creation data; the size has to
     * come from the extracted directory.
     */
    fun inspect(name: String): String {
        val root = rootfs(name)
        val meta = Shell.capture("inspect", "udocker inspect ${q(name)} 2>&1 | head -60", 120)
        val size = if (root.isDirectory) {
            Shell.capture("inspect", "du -sh ${q(root.absolutePath)} 2>/dev/null | cut -f1", 120)
        } else ""
        return buildString {
            append(meta.trim())
            append("\n\nrootfs: ").append(root.absolutePath)
            if (size.isNotBlank()) append(" (").append(size.trim()).append(")")
            if (!exists(name)) append("\nnot created yet — run it once to unpack the image")
        }
    }

    fun remove(tag: String, name: String) {
        Shell.run(tag, "udocker rm ${q(name)}", timeoutSec = 180)
    }

    /**
     * The three checks from the PRoot limitations, reported to the panel so a
     * user knows what a container cannot do on this phone.
     */
    fun capabilities(tag: String = "caps"): String {
        val command =
            "{ unshare -p /bin/true >/dev/null 2>&1 && echo 'pid namespaces: yes' " +
                "|| echo 'pid namespaces: no'; }; " +
                "{ unshare -i /bin/true >/dev/null 2>&1 && echo 'ipc namespaces: yes' " +
                "|| echo 'ipc namespaces: no'; }; " +
                "python3 -c 'import ctypes,ctypes.util as u;" +
                "l=ctypes.CDLL(u.find_library(\"c\"),use_errno=True);" +
                "l.shmget(1,4096,0o1600);" +
                "print(\"sysv ipc:\", \"no\" if ctypes.get_errno()==38 else \"yes\")' " +
                "2>/dev/null || echo 'sysv ipc: unknown'"
        return Shell.capture(tag, command, 60).lines().filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** True when the line has no shell metacharacter that `exec` would swallow. */
    private fun isPlainCommand(line: String): Boolean =
        line.isNotEmpty() && !line.any { it in ";|&<>()$`'\"\\\n" }

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
