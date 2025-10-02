package ru.ravel.ulwms.service

import android.annotation.SuppressLint
import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dalvik.system.DexClassLoader
import io.reactivex.rxjava3.core.Single
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.TlsVersion
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.net.ssl.SSLProtocolException

class ScriptLoader(
	private val context: Context,
) {
	val spec = ConnectionSpec.Builder(ConnectionSpec.Companion.MODERN_TLS)
		.tlsVersions(TlsVersion.TLS_1_2)
		.cipherSuites(
			CipherSuite.Companion.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
			CipherSuite.Companion.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
			CipherSuite.Companion.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
			CipherSuite.Companion.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
		)
		.build()
	val httpClient = OkHttpClient.Builder()
		.connectionSpecs(listOf(spec, ConnectionSpec.Companion.CLEARTEXT))
		.protocols(listOf(Protocol.HTTP_1_1))
		.retryOnConnectionFailure(false)
		.build()


	/**
	 *  Основной способ: InMemoryDexClassLoader (API 26+)
	 */
	@SuppressLint("DiscouragedPrivateApi")
	fun loadInMemory(scriptDex: File, input: Map<String, Any?>?, className: String): Map<String, Any?>? {
		require(scriptDex.exists()) { "Нет ${scriptDex.absolutePath}" }

		val optimizedDir = File(context.codeCacheDir, "groovy").apply { mkdirs() }
		val dexLoader = DexClassLoader(
			scriptDex.absolutePath,
			optimizedDir.absolutePath,
			null,
			context.classLoader
		)

		val prevCL = Thread.currentThread().contextClassLoader
		Thread.currentThread().contextClassLoader = dexLoader
		try {
			val scriptCls = dexLoader.loadClass(className)
			val ctor = scriptCls.getDeclaredConstructor().apply { isAccessible = true }
			val scriptObj = ctor.newInstance()

			val result = scriptCls.getMethod("run", Map::class.java).invoke(scriptObj, input)
			@Suppress("UNCHECKED_CAST")
			return (result as? Map<String, Any?>)
		} finally {
			Thread.currentThread().contextClassLoader = prevCL
		}
	}


	private fun wrapDexToJar(dexBytes: ByteArray, outJar: File) {
		ZipOutputStream(outJar.outputStream()).use { zos ->
			zos.putNextEntry(ZipEntry("classes.dex"))
			zos.write(dexBytes)
			zos.closeEntry()
		}
		outJar.outputStream().fd.sync()
	}


	private fun readDexDeclaredSize(ins: InputStream): Int {
		val header = ByteArray(0x70) // заголовок DEX 112 байт
		DataInputStream(ins).readFully(header)
		return ByteBuffer.wrap(header, 0x20, 4)
			.order(ByteOrder.LITTLE_ENDIAN).int
	}


	private fun verifyDexJarStrong(file: File) {
		ZipFile(file).use { z ->
			val e = z.getEntry("classes.dex") ?: error("В ${file.name} нет classes.dex")
			z.getInputStream(e).use { ins ->
				val declared = readDexDeclaredSize(ins)
				val actual = e.size.takeIf { it >= 0 } ?: error("Неизвестен размер entry")
				require(actual == declared.toLong()) {
					"Повреждённый dex в ${file.name}: header=$declared, entrySize=$actual"
				}
			}
		}
	}


	fun ensureDateAdderScript(url: String): Single<File> {
		val dir = File(context.codeCacheDir, "groovy").apply { mkdirs() }
		val outFile = File(dir, "DateAdderScript-dex.jar")

		return if (outFile.exists() && verifyDexJarLight(outFile)) {
			Single.just(outFile.also { hardenDexFile(it) })
		} else {
			val part = File(dir, "DateAdderScript-dex.jar.part").apply { delete() }

			downloadFileOnce(url, part).map { file ->
				if (isRawDex(file)) {
					val jarTmp = File(dir, "DateAdderScript-dex.jar.make")
					wrapDexToJar(file.readBytes(), jarTmp)
					replaceAtomically(jarTmp, file)
				}

				verifyDexJarStrong(file)
				replaceAtomically(file, outFile)
				hardenDexFile(outFile)
				outFile
			}
		}
	}


	private fun downloadFileOnce(url: String, dest: File, maxAttempts: Int = 99): Single<File> {
//		if (dest.exists()) dest.delete()
		return Single
			.defer {
				RetrofitProvider.api.downloadFile(url)
					.map { body ->
						body.use {
							val expected = it.contentLength().takeIf { len -> len > 0 }
							dest.outputStream().use { out ->
								it.byteStream().copyTo(out)
							}
							if (expected != null && dest.length() != expected) {
								error("Размер не совпал: ${dest.length()} / $expected")
							}
							dest
						}
					}
			}
			.retryWhen { errors ->
				errors.zipWith(
					io.reactivex.rxjava3.core.Flowable.range(1, maxAttempts)
				) { e, attempt ->
					if (e is SSLProtocolException && attempt < maxAttempts) {
						RetrofitProvider.resetClient()
						Thread.sleep(1000)
						attempt
					} else {
						throw e
					}
				}
			}
	}


	/**
	 * Атомарная замена файла (renameTo как операция перемещения в пределах тома).
	 */
	private fun replaceAtomically(src: File, dst: File) {
		if (dst.exists()) dst.delete()
		val ok = src.renameTo(dst)
		require(ok) { "Не удалось переименовать ${src.name} -> ${dst.name}" }
	}


	/**
	 * Признак «сырых» DEX-байт (не jar).
	 */
	private fun isRawDex(file: File): Boolean {
		if (!file.exists() || file.length() < 8) return false
		val header = file.inputStream().use { ins ->
			ByteArray(8).also { ins.read(it) }
		}
		return (header[0] == 'd'.code.toByte()
				&& header[1] == 'e'.code.toByte()
				&& header[2] == 'x'.code.toByte()
				&& header[3] == '\n'.code.toByte())
	}


	private fun verifyDexJarLight(file: File): Boolean {
		return try {
			ZipFile(file).use { zip ->
				val entries = zip.entries().toList().map { it.name }
				val hasDex = entries.any { it.endsWith("classes.dex") }
				if (!hasDex) {
					Log.e("ScriptLoader", "В jar нет classes.dex, есть: $entries")
				}
				hasDex
			}
		} catch (e: Exception) {
			Log.e("ScriptLoader", "Не удалось открыть как zip: ${e.message}")
			false
		}
	}


	@SuppressLint("SetWorldReadable")
	private fun hardenDexFile(file: File) {
		try {
			Os.chmod(file.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IRGRP or OsConstants.S_IROTH)
		} catch (_: Throwable) {
			file.setReadable(true, false)
			file.setWritable(false, false)
			// file.setExecutable(false, false)
		}
	}

}