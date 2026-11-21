package app.kite.core

import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.Inflater

actual class KLock actual constructor() {
    private val l = ReentrantLock()
    actual fun lock() = l.lock()
    actual fun unlock() = l.unlock()
}

actual object Disk {
    actual fun read(path: String): ByteArray? = try {
        File(path).takeIf { it.exists() }?.readBytes()
    } catch (e: Exception) { null }

    actual fun write(path: String, bytes: ByteArray): Boolean = try {
        val f = File(path)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(f) || run { f.delete(); tmp.renameTo(f) }
    } catch (e: Exception) { false }

    actual fun delete(path: String) { File(path).delete() }
}

actual fun zlibInflate(data: ByteArray, offset: Int, length: Int, size: Int): ByteArray? {
    val out = ByteArray(size)
    val inflater = Inflater()
    return try {
        inflater.setInput(data, offset, length)
        if (inflater.inflate(out) != size) null else out
    } catch (e: Exception) {
        null
    } finally {
        inflater.end()
    }
}

actual fun foldAccents(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

actual fun systemLanguage(): String = Locale.getDefault().language
