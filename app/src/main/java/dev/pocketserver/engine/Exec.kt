package dev.pocketserver.engine

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs host commands (proot, tar, su, …) and streams their merged output
 * into the console so the UI shows exactly what happened.
 */
object Exec {

    fun run(
        tag: String,
        cmd: List<String>,
        env: Map<String, String> = emptyMap(),
        workingDir: File? = null,
        stdin: String? = null,
        timeoutSec: Long = 0
    ): Int {
        EngineState.log(tag, "$ ${cmd.joinToString(" ")}")
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        if (workingDir != null) pb.directory(workingDir)
        pb.environment().putAll(env)

        val process = try {
            pb.start()
        } catch (t: Throwable) {
            EngineState.log(tag, "ERR cannot start: ${t.message}")
            return -1
        }

        try {
            if (stdin != null) {
                process.outputStream.use { it.write(stdin.toByteArray()) }
            } else {
                process.outputStream.close()
            }
        } catch (_: Throwable) {
        }

        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> if (line.isNotBlank()) EngineState.log(tag, line) }
            }
        } catch (t: Throwable) {
            EngineState.log(tag, "ERR reading output: ${t.message}")
        }

        val finished = if (timeoutSec > 0) process.waitFor(timeoutSec, TimeUnit.SECONDS) else {
            process.waitFor(); true
        }
        if (!finished) {
            process.destroyForcibly()
            EngineState.log(tag, "ERR timeout after ${timeoutSec}s")
            return -1
        }
        val code = process.exitValue()
        if (code != 0) EngineState.log(tag, "exit code $code")
        return code
    }
}
