package ru.ravel.ulwms.utils

import java.io.File

class FileUtil {

}

fun File.sha256(): String {
	val digest = java.security.MessageDigest.getInstance("SHA-256")
	inputStream().use { stream ->
		val buffer = ByteArray(8192)
		var read: Int
		while (stream.read(buffer).also { read = it } != -1) {
			digest.update(buffer, 0, read)
		}
	}
	return digest.digest().joinToString("") { "%02x".format(it) }
}