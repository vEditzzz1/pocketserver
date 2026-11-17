package dev.pocketserver.engine

import org.json.JSONArray
import org.json.JSONObject

/** One `docker run -v host:guest` pair; PRoot applies it as `-b host:guest`. */
data class Volume(val host: String, val guest: String) {
    fun toJson(): JSONObject = JSONObject().put("host", host).put("guest", guest)

    companion object {
        fun fromJson(o: JSONObject) = Volume(
            host = o.optString("host", ""),
            guest = o.optString("guest", "")
        )
    }
}

/**
 * The parts of a container's configuration that PocketServer can honour.
 *
 * A container site is started by entering the unpacked image rootfs with a
 * single PRoot, never by `udocker run` (that would nest PRoot inside PRoot),
 * so each field is translated into a PRoot argument or a shell statement:
 *
 *  - [volumes]  -> `-b host:guest`, like `udocker run --volume`
 *  - [workdir]  -> `cd` in the wrapper, like `--workdir` (works in every mode)
 *  - [env]      -> `export` before the command, like `--env`
 *  - [user]     -> `-i uid:gid` instead of `-0`, like `--user`
 *  - [entrypoint] -> prefixed to the command, like `--entrypoint`
 *  - [platform] -> `udocker pull --platform=…`
 *
 * Options that cannot exist without namespaces or cgroups (`--hostname`,
 * `--net`, `--publish` as a real NAT mapping, `--cpus`, `--memory`) are not
 * modelled here on purpose: the panel reports them as unavailable instead of
 * pretending to apply them.
 */
data class ContainerSpec(
    val platform: String = "",
    val entrypoint: String = "",
    val workdir: String = "/",
    val user: String = "0:0",
    val env: List<Pair<String, String>> = emptyList(),
    val volumes: List<Volume> = emptyList()
) {

    /** `0:0` is PRoot's `-0`; anything else becomes `-i uid:gid`. */
    val identity: String get() = if (user.isBlank()) "0:0" else user

    val isRoot: Boolean get() = identity == "0:0" || identity == "0"

    /** Absolute guest path, never empty. */
    fun guestWorkdir(): String {
        val dir = workdir.trim()
        return when {
            dir.isEmpty() -> "/"
            dir.startsWith("/") -> dir
            else -> "/$dir"
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("platform", platform)
        put("entrypoint", entrypoint)
        put("workdir", guestWorkdir())
        put("user", identity)
        // An array keeps the user's order (a JSONObject would sort them).
        put(
            "env",
            JSONArray().apply {
                env.forEach { (key, value) ->
                    put(JSONObject().put("key", key).put("value", value))
                }
            }
        )
        put(
            "volumes",
            JSONArray().apply { volumes.forEach { put(it.toJson()) } }
        )
    }

    /** Trimmed, de-duplicated and sanitised: what actually reaches the guest. */
    fun normalized(): ContainerSpec = copy(
        platform = platform.trim(),
        entrypoint = entrypoint.trim(),
        workdir = guestWorkdir(),
        user = user.trim().ifBlank { "0:0" },
        env = env
            .map { it.first.trim() to it.second }
            .filter { ENV_KEY.matches(it.first) }
            .distinctBy { it.first },
        volumes = volumes
            .map { Volume(it.host.trim(), it.guest.trim()) }
            .filter { it.host.isNotEmpty() && it.guest.isNotEmpty() }
            .map { if (it.guest.startsWith("/")) it else it.copy(guest = "/" + it.guest) }
            .distinctBy { it.guest }
    )

    companion object {
        /** Shell-safe environment variable name. */
        val ENV_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** `uid` or `uid:gid`; empty means the emulated root. */
        val USER = Regex("(\\d+)(:\\d+)?")

        val IMAGE = Regex("[a-zA-Z0-9][a-zA-Z0-9_.\\-/]*(:[a-zA-Z0-9_.\\-]+)?")

        fun fromJson(o: JSONObject?): ContainerSpec {
            if (o == null) return ContainerSpec()
            val list = ArrayList<Pair<String, String>>()
            val rows = o.optJSONArray("env")
            if (rows != null) {
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    list.add(row.optString("key", "") to row.optString("value", ""))
                }
            } else {
                val vars = o.optJSONObject("env")
                vars?.keys()?.forEach { key -> list.add(key to vars.optString(key, "")) }
            }
            val vols = ArrayList<Volume>()
            val array = o.optJSONArray("volumes")
            if (array != null) {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { vols.add(Volume.fromJson(it)) }
                }
            }
            return ContainerSpec(
                platform = o.optString("platform", ""),
                entrypoint = o.optString("entrypoint", ""),
                workdir = o.optString("workdir", "/"),
                user = o.optString("user", "0:0"),
                env = list,
                volumes = vols
            ).normalized()
        }
    }
}
