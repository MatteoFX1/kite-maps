package app.kite.core

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.zlib.*
import platform.posix.memcpy

actual class KLock actual constructor() {
    private val l = NSRecursiveLock()
    actual fun lock() = l.lock()
    actual fun unlock() = l.unlock()
}

actual object Disk {
    actual fun read(path: String): ByteArray? {
        val d = NSData.dataWithContentsOfFile(path) ?: return null
        return d.toBytes()
    }

    actual fun write(path: String, bytes: ByteArray): Boolean {
        val dir = (path as NSString).stringByDeletingLastPathComponent
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        return bytes.toNSData().writeToFile(path, atomically = true)
    }

    actual fun delete(path: String) { NSFileManager.defaultManager.removeItemAtPath(path, null) }
}

fun NSData.toBytes(): ByteArray {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    val out = ByteArray(n)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }

actual fun zlibInflate(data: ByteArray, offset: Int, length: Int, size: Int): ByteArray? {
    if (length <= 0 || size <= 0) return null
    val out = ByteArray(size)
    return memScoped {
        val strm = alloc<z_stream>()
        data.usePinned { src ->
            out.usePinned { dst ->
                strm.next_in = src.addressOf(offset).reinterpret()
                strm.avail_in = length.toUInt()
                strm.next_out = dst.addressOf(0).reinterpret()
                strm.avail_out = size.toUInt()
                if (inflateInit_(strm.ptr, ZLIB_VERSION, sizeOf<z_stream>().toInt()) != Z_OK) return@memScoped null
                val r = inflate(strm.ptr, Z_FINISH)
                val produced = strm.total_out.toInt()
                inflateEnd(strm.ptr)
                if ((r == Z_STREAM_END || r == Z_OK || r == Z_BUF_ERROR) && produced == size) out else null
            }
        }
    }
}

actual fun foldAccents(s: String): String =
    (s as NSString).decomposedStringWithCanonicalMapping.replace(Regex("\\p{Mn}+"), "")

actual fun systemLanguage(): String = NSLocale.currentLocale.languageCode
