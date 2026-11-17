package dev.pocketserver.engine

import java.io.File

/**
 * Locations of everything the runtime needs, and the environment / PRoot
 * argument list used to enter the Linux userland.
 */
object Paths {

    lateinit var files: File
        private set

    fun init(filesDir: File) {
        files = filesDir
        runtime.mkdirs()
        lib.mkdirs()
        tmp.mkdirs()
    }

    val runtime: File get() = File(files, "runtime")
    val proot: File get() = File(runtime, "proot")
    val loader: File get() = File(runtime, "loader")
    val lib: File get() = File(runtime, "lib")
    val rootfs: File get() = File(files, if (activeDistro() == Distros.UBUNTU) "ubuntu" else "rootfs")

    private fun activeDistro(): String = runCatching { Prefs.distro }.getOrDefault(Distros.ALPINE)
    val tmp: File get() = File(files, "tmp")
    val marker: File get() = File(runtime, ".installed")
    val shared: File get() = File("/storage/emulated/0")

    fun installed(): Boolean = proot.exists() &&
        (File(rootfs, "bin/busybox").exists() || File(rootfs, "bin/bash").exists())

    /**
     * Environment for the proot *process*: the Termux build keeps its loader
     * unbundled and links libtalloc/libandroid-shmem dynamically, so both
     * paths have to be provided explicitly.
     */
    fun env(): MutableMap<String, String> = mutableMapOf(
        "PROOT_LOADER" to loader.absolutePath,
        "PROOT_TMP_DIR" to tmp.absolutePath,
        "LD_LIBRARY_PATH" to lib.absolutePath,
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "HOME" to "/root",
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
        "TMPDIR" to "/tmp"
    )

    /** PRoot invocation that enters [rootfs] as fake root with device and storage binds. */
    fun prootArgs(workdir: String = "/root"): List<String> = prootArgsFor(rootfs, workdir)

    /**
     * Same invocation for an arbitrary rootfs: a distro or an unpacked
     * container image. Extra [binds] are host:guest path pairs, and
     * [identity] is the faked `uid:gid` (`0:0` == `-0`).
     */
    fun prootArgsFor(
        target: File,
        workdir: String = "/root",
        binds: List<Pair<String, String>> = emptyList(),
        identity: String = "0:0"
    ): List<String> {
        val args = mutableListOf(
            proot.absolutePath,
            "--link2symlink",
            "--kill-on-exit"
        )
        if (identity == "0:0" || identity == "0" || identity.isBlank()) {
            args += "-0"
        } else {
            args += listOf("-i", identity)
        }
        args += listOf(
            "-r", target.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${shared.absolutePath}:/sdcard"
        )
        binds.forEach { (host, guest) -> args += listOf("-b", "$host:$guest") }
        args += listOf("-w", workdir)
        return args
    }

    /** Full guest command: shell line executed inside the userland. */
    fun shellArgs(command: String): List<String> = prootArgs() + listOf("/bin/sh", "-c", command)
}
