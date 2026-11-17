package dev.pocketserver.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import dev.pocketserver.engine.panel.Panel
import kotlin.concurrent.thread

/**
 * Keeps the Linux userland and the sites alive while the app is in the
 * background, and exposes "stop everything" from the notification.
 */
class EngineService : Service() {

    companion object {
        const val CHANNEL_ID = "engine"
        const val NOTIFY_ID = 42
        const val ACTION_STOP_ALL = "dev.pocketserver.STOP_ALL"
        const val ACTION_STOP = "dev.pocketserver.STOP"

        fun start(ctx: Context) {
            val intent = Intent(ctx, EngineService::class.java)
            ctx.startForegroundService(intent)
        }

        fun stopAll(ctx: Context) {
            val intent = Intent(ctx, EngineService::class.java).setAction(ACTION_STOP_ALL)
            ctx.startService(intent)
        }

        fun shutdown(ctx: Context) {
            ctx.stopService(Intent(ctx, EngineService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // START_STICKY can recreate this service without the activity, and the
        // panel needs the prefs (port, token, panelAuto) either way.
        runCatching { Prefs.init(this) }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Server engine", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps PocketServer sites running"
                setShowBadge(false)
            }
        )
        // The Podroid kernel is started outside the app and outlives it; attach to
        // a running one so the dashboard and the web panel show the truth. The
        // bus receiver has to exist before adoption publishes, or the broadcast
        // is lost and the app keeps saying "stopped" about a live kernel.
        registerVmBus()
        thread(name = "vm-adopt") { runCatching { VmRunner.adoptExisting() } }
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketServer::engine").apply {
            setReferenceCounted(false)
            acquire()
        }
        // A server's control panel should come back with the app: the VM and the
        // sites outlive it, so a panel that needs a dashboard tap after every
        // restart leaves the guest unreachable.
        if (runCatching { Prefs.panelAuto }.getOrDefault(false)) {
            runCatching { Panel.start(this) }
                .onFailure { EngineState.log("panel", "not restored: ${it.message}") }
        }
    }

    /**
     * The VM lifecycle is driven from the same process now, but its log lines
     * still arrive as broadcasts, so mirroring them into [EngineState] keeps
     * the console in sync.
     */
    private fun registerVmBus() {
        val filter = android.content.IntentFilter().apply {
            addAction(VmBus.ACTION_LOG)
            addAction(VmBus.ACTION_STATE)
        }
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    VmBus.ACTION_LOG -> EngineState.appendFromVm(
                        intent.getStringExtra(VmBus.EXTRA_TEXT) ?: return
                    )
                    VmBus.ACTION_STATE -> {
                        EngineState.podroidRunning.value =
                            intent.getBooleanExtra(VmBus.EXTRA_RUNNING, false)
                        EngineState.podroidBusy.value =
                            intent.getBooleanExtra(VmBus.EXTRA_BUSY, false)
                        EngineState.busyLabel.value =
                            intent.getStringExtra(VmBus.EXTRA_LABEL) ?: ""
                        intent.getStringExtra(VmBus.EXTRA_ERROR)?.let {
                            EngineState.podroidError.value = it
                        }
                    }
                }
            }
        }, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFY_ID, buildNotification())

        when (intent?.action) {
            ACTION_STOP_ALL -> {
                // Stopping a site waits for proot and has to run off the main
                // thread; the notification is refreshed when it is done.
                kotlin.concurrent.thread(name = "stop-all") {
                    Servers.stopAll()
                    refresh()
                }
            }

            ACTION_STOP -> {
                kotlin.concurrent.thread(name = "stop-all") {
                    Servers.stopAll()
                    stopSelf()
                }
            }

            else -> {
                startAutostart()
            }
        }
        return START_STICKY
    }

    private fun startAutostart() {
        if (!Prefs.autostart) return
        Servers.profiles.filter { it.autoStart && !Servers.isRunning(it.id) }.forEach {
            Servers.start(this, it)
        }
    }

    fun refresh() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFY_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivityClass()).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAll = PendingIntent.getService(
            this, 1, Intent(this, EngineService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val count = Servers.runningCount()
        val text = if (count == 0) "No sites running" else "$count site${if (count == 1) "" else "s"} running"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PocketServer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop all", stopAll).build())
            .build()
    }

    private fun MainActivityClass(): Class<*> = Class.forName("dev.pocketserver.MainActivity")

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
