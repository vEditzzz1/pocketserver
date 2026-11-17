package dev.pocketserver.engine

import android.system.Os
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * Minimal tar.gz extractor. Android's toybox tar works too, but doing it in
 * Kotlin keeps the first-run install deterministic across OEM builds and lets
 * us preserve the modes/symlinks/hardlinks Alpine's rootfs relies on.
 */
object TarGz {

    /** Minimal ustar writer, used to ship the PRoot runtime to Shizuku in one stream. */
    fun writeTar(files: List<Pair<String, File>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((name, file) in files) {
            val data = file.readBytes()
            val header = ByteArray(512)
            val path = name.toByteArray(Charsets.UTF_8)
            System.arraycopy(path, 0, header, 0, minOf(path.size, 100))
            putOctal(header, 100, 8, if (name.endsWith("proot") || name.endsWith("loader")) 0b111_101_101 else 0b110_100_100)
            putOctal(header, 108, 8, 0)
            putOctal(header, 116, 8, 0)
            putOctal(header, 124, 12, data.size)
            putOctal(header, 136, 12, (System.currentTimeMillis() / 1000).toInt())
            header[156] = '0'.code.toByte()
            "ustar\u000000".toByteArray(Charsets.US_ASCII).copyInto(header, 257)
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            var checksum = 0
            header.forEach { checksum += it.toInt() and 0xFF }
            val sum = Integer.toOctalString(checksum).padStart(6, '0')
            sum.toByteArray(Charsets.US_ASCII).copyInto(header, 148)
            header[154] = 0
            header[155] = ' '.code.toByte()

            out.write(header)
            out.write(data)
            val pad = (512 - (data.size % 512)) % 512
            if (pad > 0) out.write(ByteArray(pad))
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun putOctal(buffer: ByteArray, offset: Int, length: Int, value: Int) {
        val text = Integer.toOctalString(value).padStart(length - 1, '0')
        text.toByteArray(Charsets.US_ASCII).copyInto(buffer, offset)
        buffer[offset + length - 1] = 0
    }

    fun extract(archive: File, dest: File): Int {
        dest.mkdirs()
        var entries = 0
        var paxPath: String? = null
        var paxLink: String? = null
        var longName: String? = null

        GZIPInputStream(archive.inputStream().buffered(65536)).use { input ->
            val header = ByteArray(512)
            while (readFully(input, header)) {
                if (header.all { it == 0.toByte() }) break

                val rawName = cstr(header, 0, 100)
                val mode = octal(header, 100, 8)
                val size = octal(header, 124, 12).toLong()
                val type = header[156].toInt().toChar()
                val link = cstr(header, 157, 100)
                val prefix = cstr(header, 345, 155)

                if (type == 'x' || type == 'g') {
                    val body = readBody(input, size)
                    if (type == 'x') {
                        for ((k, v) in parsePax(body)) {
                            when (k) {
                                "path" -> paxPath = v
                                "linkpath" -> paxLink = v
                            }
                        }
                    }
                    continue
                }
                if (type == 'L') {
                    longName = readBody(input, size).toString(Charsets.UTF_8).trim('\u0000', '\n')
                    continue
                }
                if (type == 'K') {
                    skipBody(input, size)
                    continue
                }

                val name = longName ?: paxPath ?: if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
                val target = File(dest, name.trimStart('/').trimEnd('/'))
                extractEntry(target, type, mode, link.ifEmpty { paxLink ?: "" }, input, size, dest)
                entries++
                paxPath = null
                paxLink = null
                longName = null
            }
        }
        return entries
    }

    private fun extractEntry(
        target: File,
        type: Char,
        mode: Int,
        link: String,
        input: InputStream,
        size: Long,
        root: File
    ) {
        when (type) {
            '5' -> {
                target.mkdirs()
                chmod(target, (mode and 0x1FF) or 0x1C0)
                if (size > 0) skipBody(input, size)
            }

            '2' -> {
                target.parentFile?.mkdirs()
                target.delete()
                try {
                    Files.createSymbolicLink(Paths.get(target.absolutePath), Paths.get(link))
                } catch (t: Throwable) {
                    EngineState.log("tar", "warn: symlink ${target.name} -> $link (${t.message})")
                }
                if (size > 0) skipBody(input, size)
            }

            '1' -> {
                target.parentFile?.mkdirs()
                target.delete()
                val source = File(root, link)
                try {
                    Files.createLink(Paths.get(target.absolutePath), Paths.get(source.absolutePath))
                } catch (_: Throwable) {
                    if (source.exists()) source.copyTo(target, overwrite = true)
                }
                if (size > 0) skipBody(input, size)
            }

            else -> {
                target.parentFile?.mkdirs()
                target.outputStream().buffered(65536).use { out -> copyBody(input, out, size) }
                chmod(target, mode and 0x1FF)
            }
        }
    }

    private fun chmod(file: File, mode: Int) {
        val value = if (mode == 0) 0b110_100_100 else mode
        try {
            Os.chmod(file.absolutePath, value)
        } catch (_: Throwable) {
            if (value and 0b100_000_000 != 0) file.setReadable(true, true)
            if (value and 0b010_000_000 != 0) file.setWritable(true, true)
            if (value and 0b001_000_000 != 0) file.setExecutable(true, true)
        }
    }

    private fun parsePax(body: ByteArray): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < body.size) {
            var space = i
            while (space < body.size && body[space] != ' '.code.toByte()) space++
            if (space >= body.size) break
            val len = String(body, i, space - i, Charsets.UTF_8).trim().toIntOrNull() ?: break
            if (len <= 0 || i + len > body.size) break
            val record = String(body, space + 1, i + len - space - 2, Charsets.UTF_8)
            val eq = record.indexOf('=')
            if (eq > 0) out.add(record.substring(0, eq) to record.substring(eq + 1))
            i += len
        }
        return out
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun readBody(input: InputStream, size: Long): ByteArray {
        if (size <= 0) return ByteArray(0)
        val data = ByteArray(size.toInt())
        var read = 0
        while (read < data.size) {
            val n = input.read(data, read, data.size - read)
            if (n < 0) break
            read += n
        }
        skipPadding(input, size)
        return data
    }

    private fun copyBody(input: InputStream, out: OutputStream, size: Long) {
        val buffer = ByteArray(65536)
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            remaining -= n
        }
        skipPadding(input, size)
    }

    private fun skipBody(input: InputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = (512 - (size % 512)).toInt() % 512
        if (pad > 0) readFully(input, ByteArray(pad))
    }

    private fun cstr(buffer: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && buffer[end] != 0.toByte()) end++
        return String(buffer, offset, end - offset, Charsets.UTF_8)
    }

    private fun octal(buffer: ByteArray, offset: Int, length: Int): Int {
        var i = offset
        val limit = offset + length
        while (i < limit && (buffer[i] == 0.toByte() || buffer[i] == ' '.code.toByte())) i++
        var value = 0
        var digits = 0
        while (i < limit) {
            val c = buffer[i].toInt()
            if (c < '0'.code || c > '7'.code) break
            value = value * 8 + (c - '0'.code)
            digits++
            i++
        }
        return if (digits == 0) 0 else value
    }
}
