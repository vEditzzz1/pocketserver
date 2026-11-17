package dev.pocketserver.engine

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

object Prefs {

    private const val NAME = "pocketserver"
    private lateinit var prefs: SharedPreferences

    /** Application context, kept for helpers that need one (DNS refresh). */
    private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        prefs = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        if (token.isEmpty()) token = randomToken()
    }

    fun context(): Context? = appCtx

    var mode: EngineState.Mode
        get() = runCatching {
            EngineState.Mode.valueOf(prefs.getString("mode", EngineState.Mode.PRoot.name)!!)
        }.getOrDefault(EngineState.Mode.PRoot)
        set(value) {
            prefs.edit().putString("mode", value.name).apply()
        }

    var autostart: Boolean
        get() = prefs.getBoolean("autostart", false)
        set(value) {
            prefs.edit().putBoolean("autostart", value).apply()
        }

    var bootStart: Boolean
        get() = prefs.getBoolean("bootStart", false)
        set(value) {
            prefs.edit().putBoolean("bootStart", value).apply()
        }

    var panelPort: Int
        get() = prefs.getInt("panelPort", 9090)
        set(value) {
            prefs.edit().putInt("panelPort", value).apply()
        }

    var panelLan: Boolean
        get() = prefs.getBoolean("panelLan", true)
        set(value) {
            prefs.edit().putBoolean("panelLan", value).apply()
        }

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) {
            prefs.edit().putString("token", value).apply()
        }

    /**
     * Whether the web panel should be listening. Remembered so the panel comes
     * back with the app instead of needing a dashboard tap after every restart.
     */
    var panelAuto: Boolean
        get() = prefs.getBoolean("panelAuto", false)
        set(value) {
            prefs.edit().putBoolean("panelAuto", value).apply()
        }

    /**
     * Stack ids whose packages were installed at least once. The guest
     * filesystem can be rebuilt (reset, bad extraction) without losing the
     * knowledge of what the user had, so those packages can be restored.
     */
    var stacks: Set<String>
        get() = (prefs.getString("stacks", "") ?: "")
            .split(',')
            .filter { it.isNotBlank() }
            .toSet()
        set(value) {
            prefs.edit().putString("stacks", value.sorted().joinToString(",")).apply()
        }

    fun rememberStack(id: String) {
        stacks = stacks + id
    }

    fun forgetStack(id: String) {
        stacks = stacks - id
    }

    var distro: String
        get() = prefs.getString("distro", Distros.ALPINE) ?: Distros.ALPINE
        set(value) {
            prefs.edit().putString("distro", value).apply()
        }

    fun randomToken(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
