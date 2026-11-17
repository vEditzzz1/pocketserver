package dev.pocketserver.engine

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The installable-container catalog shown in the web panel.
 *
 * Two sources, deliberately different in character:
 *  - the bundled list in `assets/panel/catalog.json`, which ships a tested
 *    command, port, environment and volumes per image and works offline;
 *  - Docker Hub's public search API for everything else. Hub only returns
 *    name/description/popularity, so those entries arrive without a command
 *    and without an icon — the panel fills in a monogram tile.
 *
 * Nothing here pulls an image; [Containers.ensure] does that when the created
 * site is started.
 */
data class CatalogImage(
    val id: String,
    val name: String,
    val image: String,
    val category: String,
    val official: Boolean,
    val icon: String,
    val color: String,
    val mono: String,
    val description: String,
    val port: Int,
    val command: String,
    val workdir: String,
    val user: String,
    val env: List<Pair<String, String>>,
    val volumes: List<Volume>,
    val note: String,
    val pulls: Long = 0,
    val remote: Boolean = false
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("image", image)
        put("category", category)
        put("official", official)
        put("icon", icon)
        put("color", color)
        put("mono", mono)
        put("description", description)
        put("port", port)
        put("command", command)
        put("workdir", workdir)
        put("user", user)
        put("note", note)
        put("pulls", pulls)
        put("remote", remote)
        put(
            "env",
            JSONArray().apply {
                env.forEach { (key, value) -> put(JSONObject().put("key", key).put("value", value)) }
            }
        )
        put("volumes", JSONArray().apply { volumes.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject, remote: Boolean = false): CatalogImage {
            val env = ArrayList<Pair<String, String>>()
            o.optJSONArray("env")?.let { array ->
                for (i in 0 until array.length()) {
                    val row = array.optJSONObject(i) ?: continue
                    env.add(row.optString("key", "") to row.optString("value", ""))
                }
            }
            val volumes = ArrayList<Volume>()
            o.optJSONArray("volumes")?.let { array ->
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { volumes.add(Volume.fromJson(it)) }
                }
            }
            val name = o.optString("name", o.optString("image", "image"))
            return CatalogImage(
                id = o.optString("id", name),
                name = name,
                image = o.optString("image", ""),
                category = o.optString("category", ""),
                official = o.optBoolean("official", false),
                icon = o.optString("icon", ""),
                color = o.optString("color", "#3A4757"),
                mono = o.optString("mono", "").ifBlank { monogram(name) },
                description = o.optString("description", ""),
                port = o.optInt("port", 0),
                command = o.optString("command", ""),
                workdir = o.optString("workdir", "/"),
                user = o.optString("user", "0:0"),
                env = env,
                volumes = volumes,
                note = o.optString("note", ""),
                pulls = o.optLong("pulls", 0),
                remote = remote
            )
        }

        /** Two characters at most, used for the tile when there is no logo. */
        fun monogram(name: String): String {
            val clean = name.substringAfterLast('/').trim()
            if (clean.isEmpty()) return "?"
            return if (clean.length <= 2) clean else clean.substring(0, 2)
        }
    }
}

object Catalog {

    private const val ASSET = "panel/catalog.json"
    private const val CACHE_MS = 10 * 60 * 1000L
    private const val HUB_SEARCH = "https://hub.docker.com/v2/search/repositories/"

    @Volatile
    private var bundled: List<CatalogImage>? = null

    @Volatile
    private var categories: List<Pair<String, String>> = emptyList()

    @Volatile
    private var cacheKey: String = ""

    @Volatile
    private var cacheAt: Long = 0

    @Volatile
    private var cacheValue: List<CatalogImage> = emptyList()

    // ------------------------------------------------------------- bundled

    fun load(): List<CatalogImage> {
        bundled?.let { return it }
        val text = runCatching {
            Prefs.context()?.assets?.open(ASSET)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            EngineState.log("catalog", "ERR bundled catalog missing")
            bundled = emptyList()
            return emptyList()
        }
        return runCatching {
            val root = JSONObject(text)
            val cats = ArrayList<Pair<String, String>>()
            root.optJSONArray("categories")?.let { array ->
                for (i in 0 until array.length()) {
                    val row = array.optJSONObject(i) ?: continue
                    cats.add(row.optString("id", "") to row.optString("label", ""))
                }
            }
            categories = cats
            val list = ArrayList<CatalogImage>()
            root.optJSONArray("images")?.let { array ->
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { list.add(CatalogImage.fromJson(it)) }
                }
            }
            bundled = list
            EngineState.log("catalog", "${list.size} bundled images in ${cats.size} categories")
            list
        }.getOrElse {
            EngineState.log("catalog", "ERR cannot read catalog: ${it.message}")
            bundled = emptyList()
            emptyList()
        }
    }

    fun categoryList(): List<Pair<String, String>> {
        load()
        return categories
    }

    fun byId(id: String): CatalogImage? = load().firstOrNull { it.id == id }

    /** Bundled entries matching a free-text query and an optional category. */
    fun search(query: String, category: String = ""): List<CatalogImage> {
        val q = query.trim().lowercase()
        return load().filter { entry ->
            val inCategory = category.isBlank() || entry.category == category
            val inQuery = q.isEmpty() ||
                entry.name.lowercase().contains(q) ||
                entry.image.lowercase().contains(q) ||
                entry.description.lowercase().contains(q) ||
                entry.id.lowercase().contains(q)
            inCategory && inQuery
        }
    }

    // -------------------------------------------------------------- docker

    /**
     * Docker Hub search. Returns null when the phone cannot reach it, so the
     * panel can say "offline" instead of silently showing nothing.
     */
    fun remote(query: String): List<CatalogImage>? {
        val q = query.trim().ifBlank { "alpine" }
        val key = q.lowercase()
        val now = System.currentTimeMillis()
        if (key == cacheKey && now - cacheAt < CACHE_MS) return cacheValue
        val url = "$HUB_SEARCH?query=${URLEncoder.encode(q, "UTF-8")}&page_size=25"
        val text = runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "PocketServer")
            try {
                if (conn.responseCode !in 200..299) null
                else conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            EngineState.log("catalog", "hub search failed — using the bundled catalog only")
            return null
        }
        val list = runCatching {
            val results = JSONObject(text).optJSONArray("results") ?: JSONArray()
            val out = ArrayList<CatalogImage>()
            for (i in 0 until results.length()) {
                val row = results.optJSONObject(i) ?: continue
                val repo = row.optString("repo_name", "").trim()
                if (repo.isEmpty()) continue
                // "library/redis" and "redis" are the same image; udocker pulls
                // the short form.
                val path = repo.removePrefix("library/")
                out.add(
                    CatalogImage(
                        id = "hub:$path",
                        name = path.substringAfterLast('/'),
                        image = path,
                        category = "hub",
                        official = row.optBoolean("is_official", false),
                        icon = "",
                        color = "#3A4757",
                        mono = CatalogImage.monogram(repo),
                        description = row.optString("short_description", ""),
                        port = 0,
                        command = "",
                        workdir = "/",
                        user = "0:0",
                        env = emptyList(),
                        volumes = emptyList(),
                        note = "",
                        pulls = row.optLong("pull_count", 0),
                        remote = true
                    )
                )
            }
            out
        }.getOrElse {
            EngineState.log("catalog", "ERR reading hub results: ${it.message}")
            null
        }
        if (list == null) return null
        cacheKey = key
        cacheAt = now
        cacheValue = list
        return list
    }

    /**
     * What the panel asks for: bundled hits first, then Hub hits that are not
     * already in the bundled list.
     */
    fun find(query: String, category: String, includeRemote: Boolean): JSONObject {
        val local = search(query, category)
        // Compare the full repository path, but treat "library/x" as "x":
        // cimg/postgres is a different image from postgres.
        fun repo(image: String) = image.substringBefore(':').removePrefix("library/")
        val seen = local.map { repo(it.image) }.toSet()
        // An empty query must not become a Hub search for "alpine": the Hub
        // only answers with a random slice of its 50k repositories.
        val wantRemote = includeRemote && query.isNotBlank()
        val hub = if (wantRemote) remote(query) else null
        val extra = hub?.filter { repo(it.image) !in seen } ?: emptyList()
        val all = local + extra
        val note = when {
            !wantRemote -> ""
            hub == null -> "Docker Hub unreachable — showing the built-in catalog"
            else -> ""
        }
        return JSONObject()
            .put("images", JSONArray().apply { all.forEach { put(it.toJson()) } })
            .put("remote", hub != null)
            .put("note", note)
            .put(
                "categories",
                JSONArray().apply {
                    categories.forEach { (id, label) ->
                        put(JSONObject().put("id", id).put("label", label))
                    }
                    if (extra.isNotEmpty()) {
                        put(JSONObject().put("id", "hub").put("label", "Docker Hub"))
                    }
                }
            )
    }
}
