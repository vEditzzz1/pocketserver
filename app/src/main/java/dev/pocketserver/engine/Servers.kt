package dev.pocketserver.engine

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import kotlin.concurrent.thread

/**
 * Owns every configured site and the guest processes serving them.
 * Everything that runs goes through [Shell], so PRoot, root and Shizuku
 * share the same code path.
 */
object Servers {

    const val DEFAULT_ROOT = "/storage/emulated/0/Android/data/dev.pocketserver/files/sites"
    private const val FILE = "servers.json"

    /**
     * Sites live in the app's external files dir by default: always writable
     * without storage permissions, and still reachable from a file manager.
     */
    fun sitesRoot(ctx: Context): java.io.File {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return java.io.File(base, "sites").apply { mkdirs() }
    }

    val profiles = mutableStateListOf<ServerProfile>()

    /**
     * Bumped whenever the set of live processes changes. The UI observes this
     * instead of keeping a second "running" list that could drift out of sync
     * with the processes it describes.
     */
    private val revision = androidx.compose.runtime.mutableStateOf(0)

    fun runningCount(): Int {
        revision.value
        return processes.size
    }

    private val processes = java.util.concurrent.ConcurrentHashMap<String, Process>()
    private val starting = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
    private val logs = HashMap<String, ArrayList<String>>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    // ------------------------------------------------------------ storage

    fun load(ctx: Context) {
        val file = java.io.File(ctx.filesDir, FILE)
        profiles.clear()
        if (!file.exists()) {
            createDefaultSite(ctx)
            return
        }
        runCatching {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                profiles.add(ServerProfile.fromJson(array.getJSONObject(i)))
            }
        }.onFailure { EngineState.log("servers", "ERR reading config: ${it.message}") }
        if (profiles.isEmpty()) createDefaultSite(ctx)
        normalize(ctx)
    }

    /** Repoints sites whose folder is gone (e.g. a path that needed storage access). */
    private fun normalize(ctx: Context) {
        var changed = false
        for (i in profiles.indices) {
            val profile = profiles[i]
            if (java.io.File(profile.root).exists()) continue
            val fallback = java.io.File(sitesRoot(ctx), profile.name).apply { mkdirs() }
            runCatching {
                val index = java.io.File(fallback, "index.html")
                if (!index.exists()) index.writeText(defaultIndex())
            }
            profiles[i] = profile.copy(root = fallback.absolutePath)
            EngineState.log("servers", "${profile.name}: root moved to ${fallback.absolutePath}")
            changed = true
        }
        if (changed) save(ctx)
    }

    fun save(ctx: Context) {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        runCatching { java.io.File(ctx.filesDir, FILE).writeText(array.toString(2)) }
            .onFailure { EngineState.log("servers", "ERR saving config: ${it.message}") }
    }

    private fun createDefaultSite(ctx: Context) {
        val root = java.io.File(sitesRoot(ctx), "www")
        root.mkdirs()
        val index = java.io.File(root, "index.html")
        runCatching { if (!index.exists()) index.writeText(defaultIndex()) }
            .onFailure { EngineState.log("servers", "ERR cannot write index: ${it.message}") }
        profiles.add(ServerProfile(name = "www", kind = ServerKind.STATIC, port = 8080, root = root.absolutePath))
        save(ctx)
        EngineState.log("servers", "created default site at ${root.absolutePath}")
    }

    fun newSite(ctx: Context, kind: ServerKind = ServerKind.STATIC): ServerProfile {
        val used = profiles.map { it.port }.toSet()
        var port = 8080
        while (used.contains(port)) port++
        val name = "site$port"
        val root = java.io.File(sitesRoot(ctx), name)
        root.mkdirs()
        val index = java.io.File(root, "index.html")
        runCatching { if (!index.exists()) index.writeText(defaultIndex()) }
        val profile = ServerProfile(name = name, kind = kind, port = port, root = root.absolutePath)
        profiles.add(profile)
        save(ctx)
        EngineState.log("servers", "new ${kind.label} site ${profile.name} on port $port")
        return profile
    }

    /**
     * A site backed by a Docker image. The image is pulled on first start, so
     * creating the site is instant and offline-safe.
     */
    fun newContainerSite(ctx: Context, image: String, command: String): ServerProfile {
        val used = profiles.map { it.name }.toSet()
        var n = 1
        while (used.contains("ctr$n")) n++
        val name = "ctr$n"
        val root = java.io.File(sitesRoot(ctx), name).apply { mkdirs() }
        // A container needs a real port, otherwise its "Open" URL is :0.
        val taken = profiles.map { it.port }.toSet()
        var port = 8090
        while (taken.contains(port)) port++
        val profile = ServerProfile(
            name = name,
            kind = ServerKind.CONTAINER,
            port = port,
            root = root.absolutePath,
            body = command,
            image = image.ifBlank { "alpine" }
        )
        profiles.add(profile)
        save(ctx)
        EngineState.log(
            "servers",
            "new container site ${profile.name} for ${profile.image} on port $port"
        )
        return profile
    }

    /**
     * Applies a configuration edit from the panel. The spec is normalised here
     * so an invalid value can never reach the guest shell.
     */
    fun updateContainer(
        ctx: Context,
        profile: ServerProfile,
        image: String,
        command: String,
        spec: ContainerSpec,
        port: Int,
        autoStart: Boolean
    ) {
        val wasRunning = isRunning(profile.id)
        val changedImage = image.trim().isNotEmpty() && image.trim() != profile.image
        profile.image = image.trim().ifBlank { profile.image }
        profile.body = command.trim()
        profile.container = spec.normalized()
        profile.port = port
        profile.autoStart = autoStart
        save(ctx)
        EngineState.log(
            "servers",
            "${profile.name}: configuration saved (${profile.image}, port ${profile.port}, " +
                "${profile.container.env.size} env, ${profile.container.volumes.size} volume(s))"
        )
        // A new tag only takes effect after the rootfs is unpacked again.
        if (changedImage && wasRunning) {
            EngineState.log("servers", "${profile.name}: restart to apply the new image")
        }
    }

    fun remove(ctx: Context, profile: ServerProfile) {
        stop(profile)
        profiles.remove(profile)
        save(ctx)
    }

    // ----------------------------------------------------------- commands

    fun guestPath(host: String): String = if (host.startsWith("/storage/emulated/0")) {
        "/sdcard" + host.removePrefix("/storage/emulated/0")
    } else host

    fun commandFor(p: ServerProfile): String = when (p.kind) {
        // Alpine keeps httpd in busybox-extras, Ubuntu in busybox-static without
        // the applet symlinks; one probe covers both, and `exec` keeps the pid
        // the start wrapper recorded.
        ServerKind.STATIC -> {
            val root = guestPath(p.root)
            "sh -c " + q(
                "if command -v httpd >/dev/null 2>&1; then exec httpd -f -p ${p.port} -h ${q(root)}; " +
                    "else exec busybox httpd -f -p ${p.port} -h ${q(root)}; fi"
            )
        }
        ServerKind.PHP -> "sh -c 'cd ${guestPath(p.root)} && exec php -S 0.0.0.0:${p.port} -t .'"
        ServerKind.NODE -> "sh -c 'cd ${guestPath(p.root)} && exec env PORT=${p.port} node app.js'"
        ServerKind.PYTHON -> "python3 -m http.server ${p.port} -d ${guestPath(p.root)}"
        ServerKind.CONTAINER -> Containers.commandFor(p)
        ServerKind.CUSTOM -> p.body
    }

    /** udocker container name for this site: stable across restarts. */
    fun containerName(p: ServerProfile): String = "ps-${p.id}"

    /**
     * Recorded guest pid. PRoot does not pass signals on to what it started, so
     * killing the proot process alone leaves the server running and holding its
     * port; the pid file lets [stop] kill the real process and lets [start]
     * clear a leftover from an earlier crash.
     *
     * Containers have their own `/tmp`, so their pid file lives in the site
     * folder, which is visible from the distro (/sdcard/...) and from the
     * container (/site) alike.
     */
    private fun pidFile(p: ServerProfile): String =
        if (p.kind == ServerKind.CONTAINER) "${guestPath(p.root)}/.pocketserver.pid"
        else "/tmp/pocketserver-${p.id}.pid"

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun startCommand(p: ServerProfile): String {
        val trace = pidFile(p)
        val body = commandFor(p)
        return when (p.kind) {
            // A compound body cannot be exec'd, so the wrapper records the
            // shell and the child it waits for.
            ServerKind.CUSTOM, ServerKind.CONTAINER ->
                "echo \$\$ > $trace; sh -c ${q(body)} & echo \$! >> $trace; wait \$!"
            else -> "echo \$\$ > $trace; exec $body"
        }
    }

    /** Kills whatever a previous run of this site left behind in the guest. */
    private fun killLeftover(tag: String, p: ServerProfile) {
        // The pid file is written by the server itself, but a reboot can make
        // that pid number belong to something else, so for the built-in kinds
        // the process name is checked before anything is killed. Container and
        // custom commands are arbitrary binaries, so their recorded pids are
        // trusted (the file is removed right after use).
        val port = p.port
        val strict = p.kind != ServerKind.CONTAINER && p.kind != ServerKind.CUSTOM
        val kill = if (strict) {
            "comm=\$(cat /proc/\$pid/comm 2>/dev/null); " +
                "case \"\$comm\" in httpd|php*|node|python3|sh|busybox) kill -9 \"\$pid\" 2>/dev/null ;; esac"
        } else {
            "kill -9 \"\$pid\" 2>/dev/null"
        }
        val scan = if (port <= 0) "" else
            "for d in /proc/[0-9]*; do " +
                "pid=\${d#/proc/}; [ \"\$pid\" = \"\$\$\" ] && continue; " +
                "cmd=\$(tr '\\0' ' ' < \$d/cmdline 2>/dev/null); " +
                // Never match the probe itself: its own command line quotes
                // the patterns below.
                "case \"\$cmd\" in *proot*|*cmdline*) continue ;; esac; " +
                "case \"\$cmd\" in " +
                "*\"httpd -f -p $port \"*|*\"-p $port \"*|*\"0.0.0.0:$port\"*|*\"http.server $port\"*) " +
                "kill -9 \"\$pid\" 2>/dev/null ;; esac; " +
                "done"
        val script =
            "f=${pidFile(p)}; " +
                "if [ -s \"\$f\" ]; then for pid in \$(cat \"\$f\"); do $kill; done; fi; " +
                "rm -f \"\$f\"; " + scan
        Shell.run(tag, script, timeoutSec = 20)
    }

    // ---------------------------------------------------------- lifecycle

    fun start(ctx: Context, p: ServerProfile): Boolean {
        // Also guards the window in which a startup is still installing the
        // site's runtime: two taps must not run two apk/apt installs at once.
        if (processes.containsKey(p.id) || !starting.add(p.id)) {
            EngineState.log("srv:${p.name}", "already starting or running")
            return true
        }
        EngineState.log(
            "srv:${p.name}",
            if (p.kind == ServerKind.CONTAINER) "starting container ${p.image}"
            else "starting ${p.kind.label} on port ${p.port}"
        )
        thread(name = "start-${p.name}") {
            try {
                launch(p)
            } finally {
                starting.remove(p.id)
            }
        }
        return true
    }

    private fun launch(p: ServerProfile) {
        val tag = "srv:${p.name}"
        // A site's runtime (php/node/python/static) is installed on demand, so
        // a fresh or reset guest filesystem never blocks a start. A container
        // site needs the udocker CLI for pull/create.
        if (!Stacks.ensureForKind(tag, p.kind)) {
            EngineState.log(tag, "ERR ${p.kind.label} runtime missing — trying anyway")
        }
        var target = Paths.rootfs
        var binds = emptyList<Pair<String, String>>()
        var identity = "0:0"
        var workdir = "/root"
        if (p.kind == ServerKind.CONTAINER) {
            val spec = p.container.normalized()
            // udocker unpacks the image; our own PRoot runs it. Calling
            // `udocker run` here would nest PRoot inside PRoot.
            if (!Containers.ensure(tag, p.image, containerName(p), spec.platform)) return
            target = Containers.rootfs(containerName(p))
            binds = Containers.bindsFor(p)
            identity = spec.identity
            workdir = "/"
            EngineState.log(
                tag,
                "running ${p.image} · workdir ${spec.guestWorkdir()} · user ${spec.identity} · " +
                    "${spec.env.size} env · ${binds.size} bind(s)"
            )
        }
        // Podroid mode runs inside its own VM image, so a PRoot rootfs is not
        // required (and may never have been installed). The same holds when a
        // running VM takes the commands, whichever mode is selected.
        if (!Shell.routesToVm() && !target.isDirectory) {
            EngineState.log(tag, "ERR rootfs missing: ${target.absolutePath}")
            return
        }
        // In the VM a site port only reaches Android through a SLIRP forward.
        if (Shell.routesToVm()) Podroid.ensureForward(p.port)
        // A server left over from an earlier run keeps the port busy.
        killLeftover(tag, p)
        val process = runCatching {
            Shell.startInRootfs(tag, target, startCommand(p), binds, workdir, identity)
        }.getOrNull() ?: return
        synchronized(logs) { logs[p.id] = ArrayList() }
        // A server that dies at once (bad port, missing binary) must not be
        // registered as running: the exit watcher below would race with this
        // assignment and leave a site stuck on "running" forever.
        val diedAtOnce = runCatching { process.exitValue() }.isSuccess
        if (diedAtOnce) {
            // Draining the pipe keeps the real error ("bind: Address in use")
            // visible in the site log and the console.
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) appendLog(p, line)
                }
            }
            EngineState.log(tag, "exited immediately — check the command or the port")
            return
        }
        processes[p.id] = process
        // Only advertise the site while this exact process is the live one:
        // a stop() in between must not be undone by this post.
        main.post { revision.value++ }
        thread(name = "log-${p.name}") {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) appendLog(p, line)
                }
            } catch (_: Throwable) {
            }
            val code = runCatching { process.exitValue() }.getOrDefault(-1)
            if (code != 0) EngineState.log("srv:${p.name}", "exited with code $code")
            // Only clear the state when this is still the registered process.
            if (processes[p.id] === process) {
                processes.remove(p.id)
                main.post { revision.value++ }
                EngineState.log("srv:${p.name}", "stopped")
            }
        }
    }

    /** Stops a site without blocking the caller (the UI thread). */
    fun stop(p: ServerProfile) {
        thread(name = "stop-${p.name}") { stopBlocking(p) }
    }

    /** Stops every known site and waits until each one is really gone. */
    fun stopAll() {
        profiles.toList().forEach { stopBlocking(it) }
    }

    private fun stopBlocking(p: ServerProfile) {
        val tag = "srv:${p.name}"
        // Clear the running flag even when the process is already gone, so a
        // crashed server can always be restarted from the UI.
        val process = processes.remove(p.id)
        main.post { revision.value++ }
        if (process == null) {
            // Nothing tracked: still clear anything an earlier run left behind.
            killLeftover(tag, p)
            return
        }
        EngineState.log(tag, "stopping")
        process.destroy()
        if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
        // PRoot exited; make sure the guest server it started is gone too.
        killLeftover(tag, p)
        EngineState.log(tag, "stopped")
    }

    fun isRunning(id: String): Boolean {
        revision.value // subscribe the composable that asks
        return processes.containsKey(id)
    }

    fun logOf(id: String): List<String> = synchronized(logs) { logs[id]?.toList() ?: emptyList() }

    private fun appendLog(p: ServerProfile, line: String) {
        synchronized(logs) {
            val buffer = logs.getOrPut(p.id) { ArrayList() }
            buffer.add(line)
            if (buffer.size > 400) buffer.removeAt(0)
        }
        EngineState.log("srv:${p.name}", line)
    }

    private fun defaultIndex() = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Served by PocketServer</title>
          <style>
            body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0B0F14;color:#E8EEF4;
                 font:16px/1.6 system-ui,sans-serif}
            .card{padding:32px;border:1px solid #24313D;border-radius:20px;background:#131A22;text-align:center}
            h1{margin:0 0 8px;font-size:22px}
            p{margin:0;color:#8FA3B8}
            code{color:#22C55E}
          </style>
        </head>
        <body>
          <div class="card">
            <h1>It works 🎉</h1>
            <p>Served from <code>Alpine Linux</code> inside PRoot.</p>
          </div>
        </body>
        </html>
    """.trimIndent() + "\n"
}
