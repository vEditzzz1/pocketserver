package dev.pocketserver.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlin.concurrent.thread

/**
 * Hosts the Podroid VM.
 *
 * The kernel itself is **not** started from here: it is handed to the Shizuku
 * server, because a kernel forked from an app process inherits the zygote seccomp
 * filter and is SIGKILLed 7–30 ms after boot (measured on this device). This
 * service keeps the bridge configured and the state current while the VM runs —
 * and since the kernel is no longer an app child, running it in the main process
 * is what makes Shizuku (bound only there) usable for the launch.
 *
 * Commands run in the *main* process: they only need the TCP bridge on
 * 127.0.0.1, so nothing but the VM lifecycle has to cross a process boundary.
 */
class VmService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // This process starts cold, so the engine singletons it uses have to be
        // initialised here too (MainActivity only initialises them in the main
        // process).
        Paths.init(filesDir)
        Prefs.init(this)
        runCatching { Servers.load(this) }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Podroid VM", NotificationManager.IMPORTANCE_LOW)
        )
        // A kernel from an earlier app life may still be running (it is started
        // outside the app on purpose). Attach to it so the dashboard and the web
        // panel show the truth instead of "stopped".
        thread(name = "vm-adopt") {
            runCatching { VmRunner.adoptExisting() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFY_ID, buildNotification())
        when (intent?.action ?: VmBus.ACTION_BOOT) {
            VmBus.ACTION_BOOT -> thread(name = "vm-boot") {
                VmBus.state(busy = true, running = EngineState.podroidRunning.value, label = "Booting Podroid VM")
                val ok = VmRunner.boot()
                VmBus.state(busy = false, running = ok, label = "")
                if (!ok) EngineState.log("vm", "VM did not come up — see the log above")
            }
            VmBus.ACTION_STOP -> thread(name = "vm-stop") {
                // Killing the kernel goes through a shell, so keep it off the
                // service's main thread.
                VmRunner.stop()
                VmBus.state(busy = false, running = false, label = "")
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PocketServer VM")
            .setContentText("Podroid Linux VM is running")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "podroid-vm"
        private const val NOTIFY_ID = 77

        fun boot(ctx: Context) {
            ctx.startService(Intent(ctx, VmService::class.java).setAction(VmBus.ACTION_BOOT))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, VmService::class.java).setAction(VmBus.ACTION_STOP))
        }
    }
}

/**
 * Cross-process bus between `:vm` and the main process. The VM process cannot
 * touch [EngineState] (each process has its own copy), so log lines and state
 * travel as package-scoped broadcasts.
 */
object VmBus {
    const val ACTION_BOOT = "dev.pocketserver.vm.BOOT"
    const val ACTION_STOP = "dev.pocketserver.vm.STOP"
    const val ACTION_LOG = "dev.pocketserver.vm.LOG"
    const val ACTION_STATE = "dev.pocketserver.vm.STATE"
    const val EXTRA_TEXT = "text"
    const val EXTRA_RUNNING = "running"
    const val EXTRA_BUSY = "busy"
    const val EXTRA_LABEL = "label"
    const val EXTRA_ERROR = "error"

    fun log(tag: String, message: String) {
        android.util.Log.i("PocketServer", "$tag: $message")
        val ctx = Podroid.appContext() ?: return
        ctx.sendBroadcast(
            Intent(ACTION_LOG).setPackage(ctx.packageName).putExtra(EXTRA_TEXT, "$tag: $message")
        )
    }

    fun state(running: Boolean, busy: Boolean, label: String) {
        val ctx = Podroid.appContext() ?: return
        ctx.sendBroadcast(
            Intent(ACTION_STATE).setPackage(ctx.packageName)
                .putExtra(EXTRA_RUNNING, running)
                .putExtra(EXTRA_BUSY, busy)
                .putExtra(EXTRA_LABEL, label)
        )
    }

    /** Reports why the VM is not running, so the dashboard can say it plainly. */
    fun error(message: String) {
        android.util.Log.w("PocketServer", "vm: $message")
        val ctx = Podroid.appContext() ?: return
        ctx.sendBroadcast(
            Intent(ACTION_STATE).setPackage(ctx.packageName)
                .putExtra(EXTRA_RUNNING, false)
                .putExtra(EXTRA_BUSY, false)
                .putExtra(EXTRA_LABEL, "")
                .putExtra(EXTRA_ERROR, message)
        )
    }
}
