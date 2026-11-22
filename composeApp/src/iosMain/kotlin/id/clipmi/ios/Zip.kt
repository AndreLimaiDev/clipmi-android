package id.clipmi.ios

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.posix.FILE
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.feof
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.ftello
import platform.posix.fwrite
import platform.posix.memset
import platform.zlib.MAX_WBITS
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.crc32
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

class ZipException(message: String) : Exception(message)

private const val CHUNK = 64 * 1024
private const val MAX32 = 0xFFFFFFFFL

private fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)

private fun ByteArray.u64(at: Int): Long = u32(at) or (u32(at + 4) shl 32)

/** Penyusun byte little-endian untuk header ZIP. */
private class Bytes {
    private val out = ArrayList<Byte>()

    fun u16(value: Int) = apply { out += value.toByte(); out += (value shr 8).toByte() }

    fun u32(value: Long) = apply { u16((value and 0xFFFF).toInt()); u16(((value shr 16) and 0xFFFF).toInt()) }

    fun bytes(value: ByteArray) = apply { value.forEach { out += it } }

    fun toByteArray() = out.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
private fun write(file: CPointer<FILE>, bytes: ByteArray) {
    if (bytes.isEmpty()) return
    val written = bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), file) }
    if (written.toLong() != bytes.size.toLong()) throw ZipException("Gagal menulis file (penyimpanan penuh?).")
}

@OptIn(ExperimentalForeignApi::class)
private fun read(file: CPointer<FILE>, size: Int): ByteArray {
    val bytes = ByteArray(size)
    if (size == 0) return bytes
    val got = bytes.usePinned { fread(it.addressOf(0), 1.convert(), size.convert(), file) }
    if (got.toLong() != size.toLong()) throw ZipException("File cadangan terpotong atau rusak.")
    return bytes
}

/**
 * Penulis ZIP sederhana (cukup untuk cadangan): entri deflate atau simpan apa adanya, nama UTF-8, tanpa ZIP64 (total
 * di bawah 4 GB). Kompatibel dengan `java.util.zip.ZipInputStream` di Android.
 */
@OptIn(ExperimentalForeignApi::class)
class ZipWriter(path: String) {
    private class Entry(val name: ByteArray, val method: Int, val crc: Long, val compressed: Long, val size: Long, val offset: Long)

    private val file: CPointer<FILE> = fopen(path, "wb") ?: throw ZipException("File cadangan tidak bisa dibuat.")
    private val entries = ArrayList<Entry>()

    /** Menambah file [source] sebagai [name]; [compress] = deflate (false untuk video/gambar yang sudah terkompresi). */
    fun add(name: String, source: String, compress: Boolean) {
        val input = fopen(source, "rb") ?: throw ZipException("Tidak bisa membaca $name.")
        try {
            val nameBytes = name.encodeToByteArray()
            val offset = ftello(file)
            val method = if (compress) 8 else 0
            write(file, localHeader(nameBytes, method, 0, 0, 0))
            val (crc, compressed, size) = if (compress) deflateInto(input) else storeInto(input)
            val end = ftello(file)
            if (end > MAX32) throw ZipException("Cadangan lebih dari 4 GB. Coba tanpa video hasil render.")
            // Isi header lokal (CRC dan ukuran) setelah datanya ditulis.
            fseeko(file, offset + 14, SEEK_SET)
            write(file, Bytes().u32(crc).u32(compressed).u32(size).toByteArray())
            fseeko(file, end, SEEK_SET)
            entries += Entry(nameBytes, method, crc, compressed, size, offset)
        } finally {
            fclose(input)
        }
    }

    fun close() {
        try {
            val start = ftello(file)
            for (e in entries) {
                write(
                    file,
                    Bytes().u32(0x02014b50).u16(20).u16(20).u16(0x0800).u16(e.method).u16(0).u16(0x21)
                        .u32(e.crc).u32(e.compressed).u32(e.size).u16(e.name.size).u16(0).u16(0).u16(0).u16(0).u32(0).u32(e.offset)
                        .bytes(e.name).toByteArray(),
                )
            }
            val size = ftello(file) - start
            if (entries.size > 0xFFFF) throw ZipException("Terlalu banyak file untuk satu cadangan.")
            write(file, Bytes().u32(0x06054b50).u16(0).u16(0).u16(entries.size).u16(entries.size).u32(size).u32(start).u16(0).toByteArray())
        } finally {
            fclose(file)
        }
    }

    private fun localHeader(name: ByteArray, method: Int, crc: Long, compressed: Long, size: Long): ByteArray =
        Bytes().u32(0x04034b50).u16(20).u16(0x0800).u16(method).u16(0).u16(0x21).u32(crc).u32(compressed).u32(size)
            .u16(name.size).u16(0).bytes(name).toByteArray()

    private fun storeInto(input: CPointer<FILE>): Triple<Long, Long, Long> = memScoped {
        val buffer = allocArray<UByteVar>(CHUNK)
        var crc = crc32(0u, null, 0u)
        var size = 0L
        while (true) {
            val n = fread(buffer, 1.convert(), CHUNK.convert(), input).toLong()
            if (n <= 0) break
            crc = crc32(crc, buffer, n.convert())
            if (fwrite(buffer, 1.convert(), n.convert(), file).toLong() != n) throw ZipException("Gagal menulis file (penyimpanan penuh?).")
            size += n
        }
        Triple(crc.toLong(), size, size)
    }

    private fun deflateInto(input: CPointer<FILE>): Triple<Long, Long, Long> = memScoped {
        val stream = alloc<z_stream>()
        memset(stream.ptr, 0, sizeOf<z_stream>().convert())
        if (deflateInit2_(stream.ptr, 6, Z_DEFLATED, -MAX_WBITS, 8, Z_DEFAULT_STRATEGY, ZLIB_VERSION, sizeOf<z_stream>().toInt()) != Z_OK) {
            throw ZipException("zlib tidak bisa dipakai.")
        }
        val inBuf = allocArray<UByteVar>(CHUNK)
        val outBuf = allocArray<UByteVar>(CHUNK)
        var crc = crc32(0u, null, 0u)
        var size = 0L
        var compressed = 0L
        try {
            var flush: Int
            do {
                val n = fread(inBuf, 1.convert(), CHUNK.convert(), input).toLong()
                if (n > 0) crc = crc32(crc, inBuf, n.convert())
                size += maxOf(0L, n)
                flush = if (n < CHUNK || feof(input) != 0) Z_FINISH else Z_NO_FLUSH
                stream.next_in = inBuf
                stream.avail_in = maxOf(0L, n).convert()
                do {
                    stream.next_out = outBuf
                    stream.avail_out = CHUNK.convert()
                    deflate(stream.ptr, flush)
                    val have = CHUNK - stream.avail_out.toInt()
                    if (have > 0 && fwrite(outBuf, 1.convert(), have.convert(), file).toLong() != have.toLong()) {
                        throw ZipException("Gagal menulis file (penyimpanan penuh?).")
                    }
                    compressed += have
                } while (stream.avail_out.toInt() == 0)
            } while (flush != Z_FINISH)
        } finally {
            deflateEnd(stream.ptr)
        }
        Triple(crc.toLong(), compressed, size)
    }
}

/** Pembaca ZIP (direktori pusat, ZIP64, deflate atau simpan), untuk cadangan dari Android maupun iOS. */
@OptIn(ExperimentalForeignApi::class)
class ZipReader(private val path: String) {
    class Entry(val name: String, val method: Int, val compressed: Long, val size: Long, val offset: Long) {
        val isDirectory: Boolean get() = name.endsWith("/")
    }

    val entries: List<Entry> = readDirectory()

    private fun readDirectory(): List<Entry> {
        val file = fopen(path, "rb") ?: throw ZipException("File cadangan tidak bisa dibuka.")
        try {
            fseeko(file, 0, SEEK_END)
            val length = ftello(file)
            val tail = minOf(length, 65_557L).toInt()
            if (tail < 22) throw ZipException("File ini bukan ZIP.")
            fseeko(file, length - tail, SEEK_SET)
            val end = read(file, tail)
            var at = -1
            for (i in tail - 22 downTo 0) {
                if (end.u32(i) == 0x06054b50L) {
                    at = i
                    break
                }
            }
            if (at < 0) throw ZipException("File ini bukan ZIP.")
            var count = end.u16(at + 10).toLong()
            var dirSize = end.u32(at + 12)
            var dirOffset = end.u32(at + 16)
            // ZIP64: lokasi direktori pusat ada di catatan akhir ZIP64.
            if ((count == 0xFFFFL || dirSize == MAX32 || dirOffset == MAX32) && at >= 20 && end.u32(at - 20) == 0x07064b50L) {
                fseeko(file, end.u64(at - 20 + 8), SEEK_SET)
                val z64 = read(file, 56)
                if (z64.u32(0) != 0x06064b50L) throw ZipException("File cadangan rusak.")
                count = z64.u64(32)
                dirSize = z64.u64(40)
                dirOffset = z64.u64(48)
            }
            if (dirSize > 64L * 1024 * 1024) throw ZipException("File cadangan rusak.")
            fseeko(file, dirOffset, SEEK_SET)
            val dir = read(file, dirSize.toInt())
            val list = ArrayList<Entry>()
            var p = 0
            repeat(count.toInt()) {
                if (dir.u32(p) != 0x02014b50L) throw ZipException("File cadangan rusak.")
                val method = dir.u16(p + 10)
                var compressed = dir.u32(p + 20)
                var size = dir.u32(p + 24)
                val nameLength = dir.u16(p + 28)
                val extraLength = dir.u16(p + 30)
                val commentLength = dir.u16(p + 32)
                var offset = dir.u32(p + 42)
                val name = dir.copyOfRange(p + 46, p + 46 + nameLength).decodeToString()
                // Nilai ZIP64 di extra 0x0001, urut: ukuran asli, ukuran terkompresi, offset (hanya yang 0xFFFFFFFF).
                var e = p + 46 + nameLength
                val extraEnd = e + extraLength
                while (e + 4 <= extraEnd) {
                    val id = dir.u16(e)
                    val len = dir.u16(e + 2)
                    if (id == 0x0001) {
                        var q = e + 4
                        if (size == MAX32) { size = dir.u64(q); q += 8 }
                        if (compressed == MAX32) { compressed = dir.u64(q); q += 8 }
                        if (offset == MAX32) { offset = dir.u64(q) }
                    }
                    e += 4 + len
                }
                list += Entry(name, method, compressed, size, offset)
                p = extraEnd + commentLength
            }
            return list
        } finally {
            fclose(file)
        }
    }

    /** Mengekstrak [entry] ke file [target] (folder induknya harus sudah ada). */
    fun extract(entry: Entry, target: String) {
        val file = fopen(path, "rb") ?: throw ZipException("File cadangan tidak bisa dibuka.")
        val out = fopen(target, "wb") ?: run {
            fclose(file)
            throw ZipException("Tidak bisa menulis ${entry.name}.")
        }
        try {
            fseeko(file, entry.offset, SEEK_SET)
            val header = read(file, 30)
            if (header.u32(0) != 0x04034b50L) throw ZipException("File cadangan rusak.")
            fseeko(file, entry.offset + 30 + header.u16(26) + header.u16(28), SEEK_SET)
            when (entry.method) {
                0 -> copy(file, out, entry.compressed)
                8 -> inflateInto(file, out, entry.compressed)
                else -> throw ZipException("Format kompresi ${entry.method} tidak didukung.")
            }
        } finally {
            fclose(out)
            fclose(file)
        }
    }

    /** Isi entri kecil (misalnya manifest) sebagai teks. */
    fun readText(entry: Entry, scratch: String): String {
        extract(entry, scratch)
        return id.clipmi.ios.readText(scratch).orEmpty().also { removeItem(scratch) }
    }

    private fun copy(input: CPointer<FILE>, out: CPointer<FILE>, length: Long) = memScoped {
        val buffer = allocArray<ByteVar>(CHUNK)
        var left = length
        while (left > 0) {
            val n = fread(buffer, 1.convert(), minOf(CHUNK.toLong(), left).convert(), input).toLong()
            if (n <= 0) throw ZipException("File cadangan terpotong.")
            if (fwrite(buffer, 1.convert(), n.convert(), out).toLong() != n) throw ZipException("Gagal menulis file (penyimpanan penuh?).")
            left -= n
        }
    }

    private fun inflateInto(input: CPointer<FILE>, out: CPointer<FILE>, length: Long) = memScoped {
        val stream = alloc<z_stream>()
        memset(stream.ptr, 0, sizeOf<z_stream>().convert())
        if (inflateInit2_(stream.ptr, -MAX_WBITS, ZLIB_VERSION, sizeOf<z_stream>().toInt()) != Z_OK) throw ZipException("zlib tidak bisa dipakai.")
        val inBuf = allocArray<UByteVar>(CHUNK)
        val outBuf = allocArray<UByteVar>(CHUNK)
        var left = length
        try {
            var result = Z_OK
            while (result != Z_STREAM_END) {
                if (stream.avail_in.toInt() == 0) {
                    if (left <= 0) break
                    val n = fread(inBuf, 1.convert(), minOf(CHUNK.toLong(), left).convert(), input).toLong()
                    if (n <= 0) throw ZipException("File cadangan terpotong.")
                    left -= n
                    stream.next_in = inBuf
                    stream.avail_in = n.convert()
                }
                stream.next_out = outBuf
                stream.avail_out = CHUNK.convert()
                result = inflate(stream.ptr, Z_NO_FLUSH)
                if (result != Z_OK && result != Z_STREAM_END && result != Z_BUF_ERROR) throw ZipException("File cadangan rusak (data terkompresi).")
                val have = CHUNK - stream.avail_out.toInt()
                if (have > 0 && fwrite(outBuf.reinterpret<ByteVar>(), 1.convert(), have.convert(), out).toLong() != have.toLong()) {
                    throw ZipException("Gagal menulis file (penyimpanan penuh?).")
                }
            }
        } finally {
            inflateEnd(stream.ptr)
        }
    }
}
