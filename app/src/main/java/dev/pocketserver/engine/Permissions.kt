package dev.pocketserver.engine

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * PocketServer targets SDK 28 (so PRoot binaries may execute), which means
 * storage is requested the classic way. Everything is asked for on first open
 * instead of failing later at a random file write.
 */
object Permissions {

    fun required(): List<String> {
        val list = mutableListOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT < 33) list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 33) list.add("android.permission.POST_NOTIFICATIONS")
        return list
    }

    fun missing(ctx: Context): List<String> = required().filter {
        ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    fun ask(activity: Activity, code: Int = 100) {
        val missing = missing(activity)
        if (missing.isEmpty()) return
        activity.requestPermissions(missing.toTypedArray(), code)
    }

    fun allFilesGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

    fun openAllFilesSettings(activity: Activity) {
        val intent = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:${activity.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${activity.packageName}"))
        }
        runCatching { activity.startActivity(intent) }
            .onFailure {
                runCatching {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
    }

    fun summary(ctx: Context): String = when {
        allFilesGranted() -> "all files access"
        missing(ctx).isEmpty() -> "app folder only"
        else -> "not granted"
    }

    /**
     * Samsung (and Android's own app-freezer) suspends a background app, which
     * stops the web panel and the guest processes with it. A server has to be
     * exempt from battery optimisation to stay reachable.
     */
    fun ignoringBattery(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    }.getOrDefault(false)

    fun requestBattery(activity: Activity) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${activity.packageName}"))
        runCatching { activity.startActivity(direct) }.onFailure {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}
