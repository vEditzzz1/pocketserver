package dev.pocketserver.engine.panel

import android.content.Context
import dev.pocketserver.engine.Catalog
import dev.pocketserver.engine.ContainerSpec
import dev.pocketserver.engine.Containers
import dev.pocketserver.engine.Distros
import dev.pocketserver.engine.EngineState
import dev.pocketserver.engine.Podroid
import dev.pocketserver.engine.Prefs
import dev.pocketserver.engine.RuntimeInstaller
import dev.pocketserver.engine.ServerKind
import dev.pocketserver.engine.ServerProfile
import dev.pocketserver.engine.Servers
import dev.pocketserver.engine.Shell
import dev.pocketserver.engine.Stacks
import dev.pocketserver.engine.Volume
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The web control panel. A tiny HTTP server that serves the bundled
 * single-page UI and a JSON API over the same engine the app uses, so a site
 * can be started from a browser on the phone or anywhere on the LAN.
 */
object Panel {

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var appContext: Context? = null
    // Cached, not fixed: every live SSE stream keeps a thread for as long as
    // its client is connected, and a fixed pool would eventually starve the
    // API (a hidden WebView stops reading and holds its stream).
    private val pool = Executors.newCachedThreadPool()
    private val streams = java.util.concurrent.CopyOnWriteArrayList<OutputStream>()

    /** Enough for a phone and a LAN browser; older streams are dropped. */
    private const val MAX_STREAMS = 4

    /** Fixed command behind the Podroid status/guest-info view. */
    private const val GUEST_INFO =
        "uname -sr; hostname; uptime | sed 's/^ *//'; echo; free -m | sed -n '1,2p'; " +
            "echo; df -h / /sdcard 2>/dev/null | sed -n '1,3p'"

    @Volatile
    private var running = false

    fun isRunning(): Boolean = running

    fun start(ctx: Context, port: Int = Prefs.panelPort): Boolean {
        if (running) return true
        appContext = ctx.applicationContext
        val bindAddress = if (Prefs.panelLan) "0.0.0.0" else "127.0.0.1"
        var bound = bind(bindAddress, port)
        if (bound == null && Podroid.releaseForward(port)) {
            // A VM booted by an older build forwarded the panel port into the
            // guest; take the port back so the panel works while the VM is on.
            Thread.sleep(500)
            bound = bind(bindAddress, port)
        }
        if (bound == null) {
            EngineState.panelRunning.value = false
            return false
        }
        val socket = bound
        server = socket
        running = true
        EngineState.panelRunning.value = true
        // Remembered by EngineService, so the panel returns with the app.
        runCatching { Prefs.panelAuto = true }
        EngineState.log("panel", "listening on http://$bindAddress:$port")
        acceptThread = thread(name = "panel-accept") {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    break
                }
                pool.execute { handleSafely(client) }
            }
        }
        return true
    }

    private fun bind(address: String, port: Int): ServerSocket? = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(address, port))
        }
    } catch (t: Throwable) {
        EngineState.log("panel", "cannot bind $port: ${t.message}")
        null
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { Prefs.panelAuto = false }
        runCatching { server?.close() }
        server = null
        streams.clear()
        EngineState.panelRunning.value = false
        EngineState.log("panel", "stopped")
    }

    // -------------------------------------------------------------- http

    private fun handleSafely(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 15000
                val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]

                var contentLength = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }
                val body = if (contentLength > 0) {
                    val chars = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(chars, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(chars, 0, read)
                } else ""

                val uri = target.substringBefore("?")
                val query = target.substringAfter("?", "")
                    .split("&")
                    .filter { it.contains("=") }
                    .associate { it.substringBefore("=") to java.net.URLDecoder.decode(it.substringAfter("="), "UTF-8") }

                val out = socket.getOutputStream()
                route(method, uri, query, body, out)
                out.flush()
            }
        } catch (_: Throwable) {
        }
    }

    private fun route(method: String, uri: String, query: Map<String, String>, body: String, out: OutputStream) {
        when {
            uri == "/" || uri == "/index.html" -> serveAsset("index.html", "text/html; charset=utf-8", out)
            uri == "/panel.css" -> serveAsset("panel.css", "text/css; charset=utf-8", out)
            uri == "/panel.js" -> serveAsset("panel.js", "application/javascript; charset=utf-8", out)
            uri == "/api/stream" -> {
                if (!authorized(query)) return json(out, 401, error("bad token"))
                stream(out)
                return
            }

            uri.startsWith("/api/") -> {
                if (!authorized(query)) return json(out, 401, error("bad token"))
                api(method, uri, query, body, out)
            }

            else -> json(out, 404, error("not found"))
        }
    }

    private fun authorized(query: Map<String, String>): Boolean {
        val token = query["token"]
        return Prefs.token.isNotEmpty() && token == Prefs.token
    }

    private fun api(method: String, uri: String, query: Map<String, String>, body: String, out: OutputStream) {
        val ctx = appContext
        try {
            when (uri) {
                "/api/state" -> json(out, 200, stateJson())
                "/api/site/start" -> {
                    val site = Servers.profiles.firstOrNull { it.id == query["id"] }
                    if (site == null) return json(out, 404, error("no such site"))
                    val ok = Servers.start(ctx!!, site)
                    json(out, 200, JSONObject().put("ok", ok))
                }

                "/api/site/stop" -> {
                    val site = Servers.profiles.firstOrNull { it.id == query["id"] }
                    if (site == null) return json(out, 404, error("no such site"))
                    Servers.stop(site)
                    json(out, 200, JSONObject().put("ok", true))
                }

                "/api/site/create" -> {
                    val site = Servers.newSite(ctx!!)
                    json(out, 200, site.toJson())
                }

                "/api/site/delete" -> {
                    val site = Servers.profiles.firstOrNull { it.id == query["id"] }
                    if (site == null) return json(out, 404, error("no such site"))
                    Servers.remove(ctx!!, site)
                    json(out, 200, JSONObject().put("ok", true))
                }

                "/api/logs" -> {
                    val id = query["id"] ?: ""
                    val lines = Servers.logOf(id)
                    json(out, 200, JSONObject().put("lines", JSONArray(lines)))
                }

                "/api/container/list" -> {
                    val images = JSONArray()
                    Containers.list().forEach { images.put(it) }
                    json(out, 200, JSONObject().put("containers", images))
                }

                // The installable-image catalog: bundled entries plus, when the
                // panel asks for it, Docker Hub search results.
                "/api/catalog" -> {
                    json(
                        out, 200,
                        Catalog.find(
                            query["q"] ?: "",
                            query["category"] ?: "",
                            query["remote"] == "1"
                        )
                    )
                }

                // One tap from the catalog: create a container site with the
                // entry's command, port, environment and volumes.
                "/api/catalog/add" -> {
                    val entry = Catalog.byId(query["id"] ?: "")
                        ?: return json(out, 404, error("unknown catalog entry"))
                    if (!ContainerSpec.IMAGE.matches(entry.image)) {
                        return json(out, 400, error("bad image name in catalog"))
                    }
                    val site = Servers.newContainerSite(ctx!!, entry.image, entry.command)
                    val taken = Servers.profiles.filter { it.id != site.id }.map { it.port }.toSet()
                    val requested = query["port"]?.toIntOrNull() ?: 0
                    var port = if (requested in 1..65535) requested else entry.port
                    if (port <= 0) port = 8100
                    if (taken.contains(port)) {
                        var candidate = maxOf(port, 8100)
                        while (taken.contains(candidate)) candidate++
                        port = candidate
                    }
                    val volumes = entry.volumes.map { volume ->
                        Volume(volume.host.replace("\$SITE", site.root), volume.guest)
                    }
                    val spec = ContainerSpec(
                        workdir = entry.workdir,
                        user = entry.user,
                        env = entry.env,
                        volumes = volumes
                    )
                    Servers.updateContainer(ctx!!, site, entry.image, entry.command, spec, port, false)
                    if (query["start"] == "1") Servers.start(ctx!!, site)
                    json(
                        out, 200,
                        JSONObject().put("ok", true).put("id", site.id).put("port", port)
                            .put("image", entry.image)
                    )
                }

                // Package sets: the cheap list (no probes) and the checked one.
                "/api/stacks" -> json(out, 200, JSONObject().put("stacks", stacksJson(false)))

                "/api/stacks/status" ->
                    json(out, 200, JSONObject().put("stacks", stacksJson(true)))

                "/api/stack/remove" -> {
                    val stack = Stacks.byId(query["id"] ?: "")
                        ?: return json(out, 404, error("unknown stack"))
                    if (query["confirm"] != "1") return json(out, 400, error("confirm required"))
                    Stacks.uninstall(ctx!!, stack)
                    json(out, 200, JSONObject().put("ok", true).put("removing", stack.label))
                }

                "/api/container/run" -> {
                    val image = query["image"] ?: return json(out, 400, error("missing image"))
                    if (!ContainerSpec.IMAGE.matches(image)) {
                        return json(out, 400, error("bad image name"))
                    }
                    val site = Servers.newContainerSite(ctx!!, image, query["command"] ?: "")
                    Servers.start(ctx!!, site)
                    json(out, 200, JSONObject().put("ok", true).put("id", site.id))
                }

                // Saves the whole configuration at once: image, command and
                // the ContainerSpec knobs. Values arrive as one JSON object so
                // repeated env/volume rows survive the query parser.
                "/api/container/config" -> {
                    val site = containerSite(query["id"])
                        ?: return json(out, 404, error("no such container site"))
                    val data = query["data"]?.let { runCatching { JSONObject(it) }.getOrNull() }
                        ?: return json(out, 400, error("missing data"))
                    val image = data.optString("image", site.image).trim()
                    if (!ContainerSpec.IMAGE.matches(image)) {
                        return json(out, 400, error("bad image name"))
                    }
                    val port = data.optInt("port", site.port)
                    if (port !in 1..65535) return json(out, 400, error("port must be 1-65535"))
                    Servers.profiles.firstOrNull { it.id != site.id && it.port == port }?.let {
                        return json(out, 400, error("port $port is already used by ${it.name}"))
                    }
                    val spec = ContainerSpec(
                        platform = data.optString("platform", ""),
                        entrypoint = data.optString("entrypoint", ""),
                        workdir = data.optString("workdir", "/"),
                        user = data.optString("user", "0:0"),
                        env = parseEnv(data),
                        volumes = parseVolumes(data, site.root)
                    )
                    if (!ContainerSpec.USER.matches(spec.user.trim().ifBlank { "0:0" })) {
                        return json(out, 400, error("run as must be uid or uid:gid"))
                    }
                    if (spec.workdir.contains("..")) {
                        return json(out, 400, error("workdir must not contain .."))
                    }
                    if (spec.env.size > 32) return json(out, 400, error("at most 32 variables"))
                    if (spec.volumes.size > 16) return json(out, 400, error("at most 16 volumes"))
                    spec.volumes.firstOrNull { it.guest.contains("..") }?.let {
                        return json(out, 400, error("volume path must not contain .."))
                    }
                    Servers.updateContainer(
                        ctx!!, site, image, data.optString("command", site.body), spec, port,
                        data.optBoolean("autoStart", site.autoStart)
                    )
                    json(
                        out, 200,
                        JSONObject().put("ok", true)
                            .put("site", site.toJson().put("running", Servers.isRunning(site.id)))
                    )
                }

                // Destructive: drops container-local changes and re-pulls. The
                // panel has to send confirm=1 (two taps).
                "/api/container/recreate" -> {
                    val site = containerSite(query["id"])
                        ?: return json(out, 404, error("no such container site"))
                    if (query["confirm"] != "1") return json(out, 400, error("confirm required"))
                    Servers.stop(site)
                    val name = Servers.containerName(site)
                    val image = site.image
                    val platform = site.container.normalized().platform
                    val tag = "ctr:${site.name}"
                    thread(name = "container-recreate") {
                        Containers.recreate(tag, name, image, platform)
                    }
                    json(out, 200, JSONObject().put("ok", true).put("recreating", image))
                }

                "/api/container/pull" -> {
                    val image = query["image"] ?: return json(out, 400, error("missing image"))
                    if (!ContainerSpec.IMAGE.matches(image)) {
                        return json(out, 400, error("bad image name"))
                    }
                    val platform = query["platform"] ?: ""
                    thread(name = "container-pull") {
                        Containers.pull("pull", image, platform)
                    }
                    json(out, 200, JSONObject().put("ok", true).put("pulling", image))
                }

                "/api/container/inspect" -> {
                    val site = containerSite(query["id"])
                        ?: return json(out, 404, error("no such container site"))
                    val name = Servers.containerName(site)
                    json(
                        out, 200,
                        JSONObject()
                            .put("ok", true)
                            .put("container", name)
                            .put("installed", Containers.exists(name))
                            .put("info", Containers.inspect(name))
                            .put("capabilities", Containers.capabilities())
                    )
                }

                "/api/container/stop" -> {
                    val site = containerSite(query["id"])
                        ?: return json(out, 404, error("no such container site"))
                    Servers.stop(site)
                    json(out, 200, JSONObject().put("ok", true))
                }

                "/api/container/remove" -> {
                    val site = containerSite(query["id"])
                        ?: return json(out, 404, error("no such container site"))
                    Servers.stop(site)
                    thread(name = "container-rm") {
                        Containers.remove("panel", Servers.containerName(site))
                    }
                    Servers.profiles.remove(site)
                    Servers.save(ctx!!)
                    json(out, 200, JSONObject().put("ok", true))
                }

                "/api/runtime/repair" -> {
                    // Repairs the runtime in place; installed packages survive.
                    RuntimeInstaller.install(ctx!!)
                    json(out, 200, JSONObject().put("ok", true).put("action", "repair"))
                }

                "/api/runtime/reset" -> {
                    // Explicit user action: rebuilds the guest filesystem and
                    // restores the recorded package sets afterwards.
                    RuntimeInstaller.reset(ctx!!)
                    json(out, 200, JSONObject().put("ok", true).put("action", "reset"))
                }

                "/api/stack" -> {
                    // One-tap package sets, including stacks whose tool is not a
                    // distro package (udocker runs its own post-install step).
                    val id = query["id"] ?: return json(out, 400, error("missing id"))
                    val stack = Stacks.byId(id) ?: return json(out, 404, error("unknown stack"))
                    Stacks.install(ctx!!, stack)
                    json(
                        out, 200,
                        JSONObject().put("ok", true).put("stack", stack.id).put("label", stack.label)
                    )
                }

                "/api/pkg" -> {
                    val add = query["add"] ?: return json(out, 400, error("missing add"))
                    val safe = add.split(",").map { it.trim() }.filter { it.matches(Regex("[a-z0-9._+-]+")) }
                    if (safe.isEmpty()) return json(out, 400, error("bad package name"))
                    thread(name = "pkg") {
                        Shell.run("pkg", Stacks.installNamesCommand(safe), timeoutSec = 600)
                        Stacks.all.firstOrNull { safe.containsAll(Stacks.packages(it)) }
                            ?.let { Prefs.rememberStack(it.id) }
                    }
                    json(out, 200, JSONObject().put("ok", true).put("installing", JSONArray(safe)))
                }

                "/api/mode" -> {
                    val mode = query["set"] ?: return json(out, 400, error("missing set"))
                    val parsed = runCatching { EngineState.Mode.valueOf(mode) }.getOrNull()
                        ?: return json(out, 400, error("bad mode"))
                    EngineState.mode.value = parsed
                    Prefs.mode = parsed
                    json(out, 200, JSONObject().put("mode", parsed.name))
                }

                "/api/distro" -> {
                    // Switching userland stops the Podroid VM and every site, so
                    // the next guest command cannot land in the wrong image.
                    val name = query["set"] ?: return json(out, 400, error("missing set"))
                    if (name != Distros.ALPINE && name != Distros.UBUNTU) {
                        return json(out, 400, error("bad distro"))
                    }
                    Distros.switchTo(ctx!!, name)
                    json(
                        out, 200,
                        JSONObject().put("ok", true)
                            .put("distro", name)
                            .put("label", Distros.label(name))
                    )
                }

                "/api/podroid" -> json(out, 200, podroidJson())

                "/api/podroid/boot" -> {
                    // Async: boot() reports through the state stream.
                    Podroid.boot()
                    json(out, 200, JSONObject().put("ok", true))
                }

                "/api/podroid/stop" -> {
                    Podroid.stop()
                    json(out, 200, JSONObject().put("ok", true))
                }

                // Everything below talks to the VM directly instead of going
                // through the selected runtime mode. While the kernel is up it
                // owns the forwarded ports, so a PRoot answer would be a lie.
                "/api/podroid/guest" -> {
                    if (!EngineState.podroidRunning.value) {
                        return json(
                            out, 200,
                            JSONObject().put("ok", false).put("rc", -1).put("output", "")
                        )
                    }
                    val (rc, text) = Podroid.rawExec(GUEST_INFO, 25_000)
                    json(out, 200, JSONObject().put("ok", rc == 0).put("rc", rc).put("output", text))
                }

                "/api/podroid/exec" -> {
                    val cmd = query["cmd"]?.trim().orEmpty()
                    if (cmd.isEmpty()) return json(out, 400, error("missing cmd"))
                    if (!EngineState.podroidRunning.value) {
                        return json(out, 400, error("the VM is not running"))
                    }
                    val timeout = (query["timeout"]?.toLongOrNull() ?: 60L).coerceIn(5L, 180L)
                    val (rc, text) = Podroid.rawExec(cmd, timeout * 1000)
                    json(out, 200, JSONObject().put("ok", rc == 0).put("rc", rc).put("output", text))
                }

                "/api/podroid/reboot" -> {
                    // Stop, wait for the kernel to be gone, boot again: the
                    // panel must not have to time this itself.
                    thread(name = "podroid-reboot") { runCatching { Podroid.reboot() } }
                    json(out, 200, JSONObject().put("ok", true))
                }

                "/api/podroid/forward" -> {
                    val port = query["port"]?.toIntOrNull()
                        ?: return json(out, 400, error("bad port"))
                    if (port !in 1024..65535) {
                        return json(out, 400, error("port must be between 1024 and 65535"))
                    }
                    if (!EngineState.podroidRunning.value) {
                        return json(out, 400, error("the VM is not running"))
                    }
                    if (query["action"] == "remove") Podroid.releaseForward(port)
                    else Podroid.ensureForward(port)
                    json(
                        out, 200,
                        JSONObject().put("ok", true)
                            .put("forwarded", JSONArray(Podroid.forwardedPorts().sorted()))
                    )
                }

                "/api/podroid/log" -> {
                    val lines = (query["lines"]?.toIntOrNull() ?: 120).coerceIn(10, 400)
                    json(out, 200, JSONObject().put("output", vmLog(lines)))
                }

                "/api/podroid/install" -> {
                    if (Podroid.installed()) {
                        return json(out, 200, JSONObject().put("ok", true).put("already", true))
                    }
                    Podroid.install()
                    json(out, 200, JSONObject().put("ok", true))
                }

                // Destructive: the guest formats a fresh overlay on next boot.
                "/api/podroid/wipe" -> {
                    if (query["confirm"] != "1") return json(out, 400, error("confirm required"))
                    if (EngineState.podroidRunning.value) {
                        return json(out, 400, error("stop the VM first"))
                    }
                    val gone = runCatching { Podroid.storage.delete() }.getOrDefault(false)
                    json(out, 200, JSONObject().put("ok", gone))
                }

                else -> json(out, 404, error("unknown endpoint"))
            }
        } catch (t: Throwable) {
            json(out, 500, error(t.message ?: "error"))
        }
    }

    private fun stateJson(): JSONObject {
        val sites = JSONArray()
        val containers = JSONArray()
        Servers.profiles.forEach { site ->
            val payload = site.toJson()
                .put("running", Servers.isRunning(site.id))
                .put("url", "http://${lanAddress()}:${site.port}/")
            sites.put(payload)
            if (site.kind == ServerKind.CONTAINER) {
                containers.put(
                    payload.put("installed", Containers.exists(Servers.containerName(site)))
                )
            }
        }
        // A Shizuku server that itself runs as root hands every command it forks
        // real root, so this is not a plain unprivileged shell on such a phone.
        // Ask once (cached in Shell) and let the panel describe what is true.
        val shizukuUid = Shell.shizukuUid()
        return JSONObject()
            .put("runtime", EngineState.runtimeInstalled.value)
            .put("mode", EngineState.mode.value.name)
            .put("shizukuUid", shizukuUid ?: JSONObject.NULL)
            // While the VM runs, guest commands go into the guest whichever mode
            // is selected, so the header says what is actually in effect.
            .put(
                "modeLabel",
                when {
                    EngineState.podroidRunning.value -> "Podroid · VM"
                    EngineState.mode.value == EngineState.Mode.Shizuku && shizukuUid == 0 ->
                        "Shizuku · root"

                    else -> EngineState.mode.value.label
                }
            )
            .put("busy", EngineState.busy.value)
            .put("busyLabel", EngineState.busyLabel.value)
            .put("panelPort", Prefs.panelPort)
            .put("lan", Prefs.panelLan)
            .put("host", lanAddress())
            .put("packages", JSONArray(Prefs.stacks.toList()))
            .put("distro", Prefs.distro)
            .put("distroLabel", Distros.label(Prefs.distro))
            .put("stacks", stacksJson(false))
            .put("sites", sites)
            .put("containers", containers)
            .put("podroid", podroidJson())
    }

    /**
     * Everything the panel needs to drive the VM without the phone in hand:
     * whether the images are installed, whether the kernel runs (and its pid),
     * which ports are forwarded, and why it is down when it is.
     */
    private fun podroidJson(): JSONObject {
        val forwarded = JSONArray()
        Podroid.forwardedPorts().sorted().forEach { forwarded.put(it) }
        return JSONObject()
            .put("installed", Podroid.installed())
            .put("running", EngineState.podroidRunning.value)
            .put("busy", EngineState.podroidBusy.value)
            .put("error", EngineState.podroidError.value)
            .put("imagesMb", Podroid.sizeMb())
            .put(
                "storageMb",
                if (Podroid.storage.exists()) Podroid.storage.length() / (1L shl 20) else 0L
            )
            .put("pid", Podroid.pid())
            .put("bridge", Podroid.BRIDGE_PORT)
            .put("forwarded", forwarded)
            .put("canHost", Shell.canHostVm())
            .put(
                "shizuku",
                runCatching { Shell.shizukuAlive() && Shell.shizukuGranted() }.getOrDefault(false)
            )
    }

    /**
     * Tail of the kernel log the launcher writes (vm.log). The UML console is
     * chatty, so only the last [lines] lines are read.
     */
    private fun vmLog(lines: Int): String = runCatching {
        val file = File(Podroid.dir, "vm.log")
        if (!file.exists()) return@runCatching ""
        file.readLines().takeLast(lines).joinToString("\n")
    }.getOrDefault("")

    /**
     * Package sets for the panel. [probe] runs every stack's check in one guest
     * shell; without it the list is built from the recorded state only, which
     * is what the 1.2 s state stream can afford.
     */
    private fun stacksJson(probe: Boolean): JSONArray {
        val present = if (probe) Stacks.probeAll() else emptySet()
        val array = JSONArray()
        Stacks.all.forEach { stack ->
            array.put(
                JSONObject()
                    .put("id", stack.id)
                    .put("label", stack.label)
                    .put("packages", JSONArray(Stacks.packages(stack)))
                    .put("recorded", Prefs.stacks.contains(stack.id))
                    .put("present", present.contains(stack.id))
                    .put("probed", probe)
                    .put("sites", JSONArray(Stacks.sitesUsing(stack)))
            )
        }
        return array
    }

    /** Only container sites can be configured through the container endpoints. */
    private fun containerSite(id: String?): ServerProfile? =
        Servers.profiles.firstOrNull { it.id == id && it.kind == ServerKind.CONTAINER }

    /** `env` as `[{key,value}]` (order kept) or a plain `{KEY: value}` object. */
    private fun parseEnv(data: JSONObject): List<Pair<String, String>> {
        val list = ArrayList<Pair<String, String>>()
        val rows = data.optJSONArray("env")
        if (rows != null) {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val key = row.optString("key", "").trim()
                if (ContainerSpec.ENV_KEY.matches(key)) list.add(key to row.optString("value", ""))
            }
            return list
        }
        val map = data.optJSONObject("env") ?: return list
        map.keys().forEach { key ->
            if (ContainerSpec.ENV_KEY.matches(key)) list.add(key to map.optString(key, ""))
        }
        return list
    }

    /**
     * `volumes` as `[{host,guest}]`; the guest path is always absolute and
     * `$SITE` in a host path means the site's own folder (used by the catalog).
     */
    private fun parseVolumes(data: JSONObject, siteRoot: String): List<Volume> {
        val list = ArrayList<Volume>()
        val rows = data.optJSONArray("volumes") ?: return list
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val host = row.optString("host", "").trim().replace("\$SITE", siteRoot)
            var guest = row.optString("guest", "").trim()
            if (host.isEmpty() || guest.isEmpty()) continue
            if (!guest.startsWith("/")) guest = "/$guest"
            list.add(Volume(host, guest))
        }
        return list
    }

    private fun lanAddress(): String {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress ?: "127.0.0.1"
        } catch (_: Throwable) {
            "127.0.0.1"
        }
    }

    // ----------------------------------------------------------------- sse

    private fun stream(out: OutputStream) {
        // A client that stopped reading (backgrounded browser) would otherwise
        // hold its socket forever; keep only the newest few streams.
        while (streams.size >= MAX_STREAMS) {
            runCatching { streams.removeAt(0).close() }
        }
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: keep-alive\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n").toByteArray()
        )
        out.flush()
        streams.add(out)
        var index = maxOf(0, EngineState.console.size - 50)
        try {
            while (running) {
                val lines = EngineState.console
                while (index < lines.size) {
                    val payload = JSONObject().put("line", lines[index]).toString()
                    out.write("data: $payload\n\n".toByteArray())
                    index++
                }
                val state = "event: state\ndata: ${stateJson()}\n\n"
                out.write(state.toByteArray())
                out.flush()
                Thread.sleep(1200)
            }
        } catch (_: Throwable) {
        } finally {
            streams.remove(out)
        }
    }

    // ------------------------------------------------------------ helpers

    private fun serveAsset(name: String, type: String, out: OutputStream) {
        val bytes = appContext?.assets?.open("panel/$name")?.use { it.readBytes() }
        if (bytes == null) {
            json(out, 404, error("asset missing"))
            return
        }
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
                "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        out.write(bytes)
        out.flush()
    }

    private fun error(message: String) = JSONObject().put("error", message)

    private fun json(out: OutputStream, code: Int, payload: JSONObject) {
        val bytes = payload.toString().toByteArray()
        val status = when (code) {
            200 -> "200 OK"
            400 -> "400 Bad Request"
            401 -> "401 Unauthorized"
            404 -> "404 Not Found"
            else -> "500 Internal Server Error"
        }
        out.write(
            ("HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(bytes)
        out.flush()
    }
}
