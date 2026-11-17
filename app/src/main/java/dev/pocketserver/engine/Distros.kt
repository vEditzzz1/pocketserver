package dev.pocketserver.engine

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Optional second userland. Alpine ships inside the APK; Ubuntu is downloaded
 * on demand and can be switched to from the panel or the app.
 */
object Distros {

    const val ALPINE = "alpine"
    const val UBUNTU = "ubuntu"

    const val UBUNTU_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"

    fun label(name: String): String = when (name) {
        UBUNTU -> "Ubuntu 24.04"
        else -> "Alpine 3.24"
    }

    fun installed(ctx: Context): Boolean = File(Paths.rootfs, "bin/bash").exists() ||
        File(Paths.rootfs, "bin/busybox").exists()

    fun installUbuntu(ctx: Context) {
        if (EngineState.busy.value) {
            EngineState.log("distro", "busy: ${EngineState.busyLabel.value}")
            return
        }
        val app = ctx.applicationContext
        thread(name = "ubuntu-install") {
            EngineState.busy.value = true
            EngineState.busyLabel.value = "Downloading Ubuntu"
            try {
                stopVmForSwitch(UBUNTU)
                Servers.stopAll()
                val target = File(Paths.files, "ubuntu")
                target.deleteRecursively()
                target.mkdirs()

                val staged = File(Paths.tmp, "ubuntu.tar.gz")
                EngineState.log("distro", "downloading ${UBUNTU_URL.substringAfterLast('/')}")
                download(UBUNTU_URL, staged) { percent ->
                    EngineState.busyLabel.value = "Ubuntu $percent%"
                }
                EngineState.log("distro", "extracting Ubuntu (${staged.length() / 1024 / 1024} MB)")
                EngineState.busyLabel.value = "Extracting Ubuntu"
                val entries = TarGz.extract(staged, target)
                staged.delete()
                EngineState.log("distro", "extracted $entries entries")
                // Shizuku runs the guest as another UID, so the rootfs has to be
                // traversable exactly like the Alpine one — tar does not set the
                // mode of the directory it extracts into (it stayed 0700).
                makeTraversable(target)

                Prefs.distro = UBUNTU
                writeConfig(app)
                val code = Exec.run(
                    "distro",
                    Paths.shellArgs("cat /etc/os-release | head -2; command -v apt-get && echo APT_OK"),
                    Paths.env(),
                    timeoutSec = 120
                )
                EngineState.log("distro", if (code == 0) "Ubuntu is ready" else "ERR Ubuntu check failed")
                EngineState.runtimeInstalled.value = Paths.installed()
            } catch (t: Throwable) {
                EngineState.log("distro", "ERR Ubuntu install: ${t.message}")
                Prefs.distro = ALPINE
            } finally {
                EngineState.busy.value = false
                EngineState.busyLabel.value = ""
            }
        }
    }

    /**
     * Switches the userland. Ubuntu is downloaded on first use; the Podroid VM
     * is stopped first, because it is Alpine and would answer every guest command
     * instead of the distro being switched to.
     */
    fun switchTo(ctx: Context, name: String) {
        if (EngineState.busy.value) {
            EngineState.log("distro", "busy: ${EngineState.busyLabel.value}")
            return
        }
        if (name == UBUNTU && !File(Paths.files, "ubuntu/bin/bash").exists()) {
            installUbuntu(ctx)
            return
        }
        if (name == Prefs.distro) {
            // Re-selecting the active distro still repairs its permissions and
            // config: an install from an older build can predate both fixes.
            makeTraversable(Paths.rootfs)
            Distros.writeConfig(ctx)
            EngineState.log("distro", "${label(name)} is already active")
            return
        }
        stopVmForSwitch(name)
        Servers.stopAll()
        Prefs.distro = name
        makeTraversable(Paths.rootfs)
        Distros.writeConfig(ctx)
        EngineState.log("distro", "switched to ${label(name)}")
        EngineState.runtimeInstalled.value = Paths.installed()
        thread(name = "distro-check") {
            Exec.run(
                "distro",
                Paths.shellArgs("cat /etc/os-release | head -2; command -v apt-get apk 2>/dev/null"),
                Paths.env(),
                timeoutSec = 60
            )
        }
    }

    /**
     * Mode the guest rootfs like the Alpine one (0755): the other runtime modes
     * enter it from a different UID (Shizuku), and 0700 made those sites die with
     * no error at all.
     */
    private fun makeTraversable(root: File) {
        runCatching {
            if (!root.exists()) return@runCatching
            root.setReadable(true, false)
            root.setExecutable(true, false)
        }
    }

    /**
     * A running VM owns the site ports and takes every guest command, so it has to
     * be gone before another userland can be used. Waits for the kernel, not just
     * for the flag, or the next start fights it for 9099.
     */
    private fun stopVmForSwitch(name: String) {
        if (!Podroid.running()) return
        EngineState.log(
            "distro",
            "stopping the Podroid VM — it runs Alpine, not ${label(name)}"
        )
        Podroid.stopAndWait()
    }

    /** resolv.conf + CA store + package sources for the active rootfs. */
    fun writeConfig(ctx: Context) {
        val root = Paths.rootfs
        val dns = RuntimeInstaller.suggestedDns(ctx)
        File(root, "etc").mkdirs()
        File(root, "etc/resolv.conf").writeText(dns.joinToString("\n") { "nameserver $it" } + "\n")

        val certs = File(root, "etc/ssl/certs/ca-certificates.crt")
        runCatching {
            certs.parentFile?.mkdirs()
            File(Paths.runtime, "cacert.pem").copyTo(certs, overwrite = true)
        }

        if (Prefs.distro == UBUNTU) {
            val sources = File(root, "etc/apt/sources.list")
            runCatching {
                sources.parentFile?.mkdirs()
                sources.writeText(
                    "deb http://ports.ubuntu.com/ubuntu-ports noble main universe multiverse restricted\n" +
                        "deb http://ports.ubuntu.com/ubuntu-ports noble-updates main universe multiverse restricted\n" +
                        "deb http://ports.ubuntu.com/ubuntu-ports noble-security main universe multiverse restricted\n"
                )
            }
        } else {
            val repos = File(root, "etc/apk/repositories")
            runCatching {
                repos.parentFile?.mkdirs()
                repos.writeText(
                    "https://dl-cdn.alpinelinux.org/alpine/${RuntimeInstaller.ALPINE_BRANCH}/main\n" +
                        "https://dl-cdn.alpinelinux.org/alpine/${RuntimeInstaller.ALPINE_BRANCH}/community\n"
                )
            }
        }
    }

    private fun download(url: String, target: File, onProgress: (Int) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        connection.inputStream.use { input ->
            val total = connection.contentLengthLong
            var written = 0L
            target.outputStream().buffered(65536).use { output ->
                val buffer = ByteArray(65536)
                var lastPercent = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    if (total > 0) {
                        val percent = (written * 100 / total).toInt()
                        if (percent != lastPercent && percent % 10 == 0) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
        }
    }
}
