package dev.pocketserver.engine

import org.json.JSONObject
import java.util.UUID

enum class ServerKind(val label: String) {
    STATIC("Static files"),
    PHP("PHP"),
    NODE("Node.js"),
    PYTHON("Python"),
    CONTAINER("Container"),
    CUSTOM("Custom command")
}

data class ServerProfile(
    val id: String = UUID.randomUUID().toString().substring(0, 8),
    var name: String,
    var kind: ServerKind,
    var port: Int,
    var root: String,
    var body: String = "",
    /** Docker image for [ServerKind.CONTAINER]; empty for every other kind. */
    var image: String = "",
    /** Volumes, env, workdir, user and entrypoint for a container site. */
    var container: ContainerSpec = ContainerSpec(),
    var autoStart: Boolean = false,
    var enabled: Boolean = true
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("kind", kind.name)
        put("port", port)
        put("root", root)
        put("body", body)
        put("image", image)
        put("container", container.toJson())
        put("autoStart", autoStart)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject) = ServerProfile(
            id = o.optString("id").ifEmpty { UUID.randomUUID().toString().substring(0, 8) },
            name = o.optString("name", "site"),
            kind = runCatching { ServerKind.valueOf(o.optString("kind", "STATIC")) }
                .getOrDefault(ServerKind.STATIC),
            port = o.optInt("port", 8080),
            root = o.optString("root", Servers.DEFAULT_ROOT),
            body = o.optString("body", ""),
            image = o.optString("image", ""),
            // Profiles saved before container configuration existed simply have
            // no "container" object and keep the defaults.
            container = ContainerSpec.fromJson(o.optJSONObject("container")),
            autoStart = o.optBoolean("autoStart", false),
            enabled = o.optBoolean("enabled", true)
        )
    }
}
