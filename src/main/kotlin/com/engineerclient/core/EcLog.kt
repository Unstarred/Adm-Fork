package com.engineerclient.core

import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object EcLog {

    private const val KEEP = 15
    private val stamp = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private var out: PrintWriter? = null
    private var flushedAt = 0L

    var path: Path? = null
        private set

    @Synchronized
    fun open(gameDir: Path, header: List<String>) {
        close()
        try {
            val dir = gameDir.resolve("logs").resolve("adm")
            Files.createDirectories(dir)
            val file = dir.resolve("ec-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".log")
            out = PrintWriter(Files.newBufferedWriter(file), false)
            path = file
            header.forEach { log("SESSION", it) }
            prune(dir)
        } catch (t: Throwable) {
            out = null
            path = null
        }
    }

    fun log(tag: String, message: String) {
        synchronized(this) {
            out?.println(LocalTime.now().format(stamp) + "\t" + tag + "\t" + message.replace('\n', ' '))
            val now = System.currentTimeMillis()
            if (now - flushedAt > 1000 || tag == "WARN" || tag == "ERROR" || tag == "MARK") { out?.flush(); flushedAt = now }
        }
    }

    @Synchronized
    fun close() {
        out?.flush()
        out?.close()
        out = null
    }

    private fun prune(dir: Path) {
        try {
            Files.list(dir).use { s ->
                s.filter { it.fileName.toString().startsWith("ec-") && it.fileName.toString().endsWith(".log") }
                    .sorted { a, b -> b.fileName.toString().compareTo(a.fileName.toString()) }
                    .skip(KEEP.toLong())
                    .forEach { Files.deleteIfExists(it) }
            }
        } catch (_: Throwable) {
        }
    }
}
