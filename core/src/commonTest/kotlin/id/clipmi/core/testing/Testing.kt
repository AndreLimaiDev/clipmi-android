package id.clipmi.core.testing

import id.clipmi.core.FIXTURE_DIR
import kotlin.reflect.KClass

/**
 * Fixture desktop di `core/fixtures` (lihat tools/export-*.php), dibaca dari disk oleh tes JVM maupun simulator iOS.
 * Path relatif terhadap folder itu, dengan "/".
 */
object Fixtures {
    fun text(path: String): String = bytes(path).decodeToString()

    fun bytes(path: String): ByteArray = readFile("$FIXTURE_DIR/$path")

    /** File (bukan folder) langsung di dalam [dir], urut nama; kosong bila folder tidak ada. */
    fun files(dir: String): List<String> = listFiles("$FIXTURE_DIR/$dir").sorted()
}

/** Isi file di [path] absolut. */
expect fun readFile(path: String): ByteArray

/** Nama file biasa di folder [path] absolut, kosong bila folder tidak ada. */
expect fun listFiles(path: String): List<String>

// Fungsi assert dengan urutan argumen JUnit 4 (pesan di depan), supaya isi tes lama tidak berubah.

fun assertEquals(expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual)

fun assertEquals(message: String?, expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual, message)

fun assertEquals(expected: Double, actual: Double, delta: Double) = kotlin.test.assertEquals(expected, actual, delta)

fun assertEquals(message: String?, expected: Double, actual: Double, delta: Double) =
    kotlin.test.assertEquals(expected, actual, delta, message)

fun assertTrue(condition: Boolean) = kotlin.test.assertTrue(condition)

fun assertTrue(message: String?, condition: Boolean) = kotlin.test.assertTrue(condition, message)

fun assertFalse(condition: Boolean) = kotlin.test.assertFalse(condition)

fun assertFalse(message: String?, condition: Boolean) = kotlin.test.assertFalse(condition, message)

fun assertNull(actual: Any?) = kotlin.test.assertNull(actual)

fun assertNull(message: String?, actual: Any?) = kotlin.test.assertNull(actual, message)

fun assertSame(expected: Any?, actual: Any?) = kotlin.test.assertSame(expected, actual)

fun fail(message: String?): Nothing = kotlin.test.fail(message)

fun <T : Throwable> assertThrows(type: KClass<T>, block: () -> Unit): T {
    val error = runCatching(block).exceptionOrNull() ?: kotlin.test.fail("Tidak ada ${type.simpleName} yang dilempar")
    if (!type.isInstance(error)) kotlin.test.fail("Diharapkan ${type.simpleName}, tetapi ${error::class.simpleName}: ${error.message}")
    @Suppress("UNCHECKED_CAST")
    return error as T
}
