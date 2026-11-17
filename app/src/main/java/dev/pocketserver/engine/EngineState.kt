package dev.pocketserver.engine

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small observable state holder shared by the UI and the engine.
 * Deliberately a singleton: the engine outlives activity recreation.
 */
object EngineState {

    enum class Mode(val label: String) {
        PRoot("PRoot · app UID"),
        Root("Root · chroot"),
        Shizuku("Shizuku · shell"),
        Podroid("Podroid · bridge")
    }

    val runtimeInstalled = mutableStateOf(false)
    val runtimeVersion = mutableStateOf("")
    val mode = mutableStateOf(Mode.PRoot)
    val panelRunning = mutableStateOf(false)
    val panelPort = mutableStateOf(9090)
    val busy = mutableStateOf(false)
    val busyLabel = mutableStateOf("")
    /** Podroid VM: files on disk, kernel process alive, one-off install running. */
    val podroidInstalled = mutableStateOf(false)
    val podroidRunning = mutableStateOf(false)
    val podroidBusy = mutableStateOf(false)

    /** Why the VM is not running, when the last boot failed. */
    val podroidError = mutableStateOf("")
    val console = mutableStateListOf<String>()

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private const val MAX_LINES = 800

    fun log(tag: String, message: String) {
        val line = "${clock.format(Date())}  $tag  $message"
        console.add(line)
        if (console.size > MAX_LINES) {
            console.removeRange(0, console.size - MAX_LINES)
        }
        android.util.Log.i("PocketServer", "$tag: $message")
    }

    fun log(message: String) = log("app", message)

    /** Appends a line that was already formatted by the `:vm` process. */
    fun appendFromVm(message: String) {
        console.add("${clock.format(Date())}  $message")
        if (console.size > MAX_LINES) {
            console.removeRange(0, console.size - MAX_LINES)
        }
    }

    fun clear() = console.clear()
}
