package id.clipmi.core.testing

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.darwin.ObjCBoolVar
import platform.posix.memcpy

// Simulator iOS berbagi sistem file dengan Mac, jadi fixture dibaca langsung dari folder proyek.

@OptIn(ExperimentalForeignApi::class)
actual fun readFile(path: String): ByteArray {
    val data = NSData.dataWithContentsOfFile(path) ?: error("Fixture tidak bisa dibaca: $path")
    val size = data.length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return out
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun listFiles(path: String): List<String> {
    val fm = NSFileManager.defaultManager
    val names = fm.contentsOfDirectoryAtPath(path, null)?.filterIsInstance<String>() ?: return emptyList()
    return names.filter { name ->
        memScoped {
            val isDir = alloc<ObjCBoolVar>()
            fm.fileExistsAtPath("$path/$name", isDir.ptr) && !isDir.value
        }
    }
}
