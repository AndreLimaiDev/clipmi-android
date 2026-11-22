package id.clipmi.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
actual fun readText(path: String): String? = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)

@OptIn(ExperimentalForeignApi::class)
actual fun readBytes(path: String): ByteArray? {
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    val size = data.length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return bytes
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun writeText(path: String, text: String) {
    NSString.create(string = text).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
}

@OptIn(ExperimentalForeignApi::class)
actual fun createDirectory(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
}

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

@OptIn(ExperimentalForeignApi::class)
actual fun fileSize(path: String): Long =
    (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L

@OptIn(ExperimentalForeignApi::class)
actual fun removeItem(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, null)
}

@OptIn(ExperimentalForeignApi::class)
actual fun copyItem(from: String, to: String) {
    NSFileManager.defaultManager.removeItemAtPath(to, null)
    NSFileManager.defaultManager.copyItemAtPath(from, to, null)
}

@OptIn(ExperimentalForeignApi::class)
actual fun listDirectory(path: String): List<String> =
    NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, null)?.mapNotNull { it as? String }.orEmpty()

@OptIn(ExperimentalForeignApi::class)
actual fun moveItem(from: String, to: String) {
    NSFileManager.defaultManager.removeItemAtPath(to, null)
    NSFileManager.defaultManager.moveItemAtPath(from, to, null)
}
