package dev.pocketserver.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the engine (and autostart sites) after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Prefs.init(context)
        if (!Prefs.bootStart) return
        EngineState.log("boot", "restarting engine after boot")
        EngineService.start(context)
    }
}
