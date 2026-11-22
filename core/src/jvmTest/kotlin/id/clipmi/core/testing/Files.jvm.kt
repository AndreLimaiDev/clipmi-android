package id.clipmi.core.testing

import java.io.File

actual fun readFile(path: String): ByteArray = File(path).readBytes()

actual fun listFiles(path: String): List<String> = File(path).listFiles { f -> f.isFile }.orEmpty().map { it.name }
