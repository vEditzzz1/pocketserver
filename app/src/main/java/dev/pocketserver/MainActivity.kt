package dev.pocketserver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import dev.pocketserver.engine.EngineService
import dev.pocketserver.engine.EngineState
import dev.pocketserver.engine.Paths
import dev.pocketserver.engine.Permissions
import dev.pocketserver.engine.Podroid
import dev.pocketserver.engine.Prefs
import dev.pocketserver.engine.Servers
import dev.pocketserver.ui.DashboardScreen
import dev.pocketserver.ui.theme.Bg
import dev.pocketserver.ui.theme.PocketServerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Paths.init(filesDir)
        Prefs.init(this)
        // Check for explicit "Podroid" intent extra before loading the saved mode.
        val opt = intent.getStringExtra("com.androidharness.app.option")
        if (opt == "podroid") {
            EngineState.mode.value = EngineState.Mode.Podroid
        } else {
            EngineState.mode.value = Prefs.mode
        }
        EngineState.runtimeInstalled.value = Paths.installed()
        EngineState.podroidInstalled.value = Podroid.installed()
        runCatching { Servers.load(this) }
            .onFailure { EngineState.log("app", "ERR loading sites: ${it.message}") }

        window.statusBarColor = Bg.toArgb()
        window.navigationBarColor = Bg.toArgb()
        WindowCompat.setDecorFitsSystemWindows(window, true)

        EngineState.log(
            "app",
            "PocketServer started on Android ${android.os.Build.VERSION.RELEASE} " +
                "(${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"})"
        )

        try {
            EngineService.start(this)
        } catch (t: Throwable) {
            EngineState.log("app", "ERR service: ${t.message}")
        }

        // Ask for storage + notifications up front, not the first time a site
        // is created.
        runCatching { Permissions.ask(this) }
            .onFailure { EngineState.log("app", "ERR permissions: ${it.message}") }

        setContent {
            PocketServerTheme {
                DashboardScreen()
            }
        }
    }
}

private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt()
    )
