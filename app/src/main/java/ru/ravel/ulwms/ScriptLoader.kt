package ru.ravel.ulwms

import android.annotation.SuppressLint
import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.TlsVersion
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import okio.sink
import okio.buffer
import okio.source

class ScriptLoader(
	private val context: Context,
) {
	val spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
		.tlsVersions(TlsVersion.TLS_1_2)
		.cipherSuites(
			CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
			CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
			CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
			CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
		)
		.build()
	val httpClient = OkHttpClient.Builder()
		.connectionSpecs(listOf(spec, ConnectionSpec.CLEARTEXT))
		.protocols(listOf(Protocol.HTTP_1_1))
		.retryOnConnectionFailure(false)
		.build()


	/**
	 *  Основной способ: InMemoryDexClassLoader (API 26+)
	 */
	fun loadInMemory(scriptDex: File, input: Map<String, Any?>): Map<String, Any?> {
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
			// --- Читаем META-INF руками через ZipFile ---
			val meta = mutableMapOf<String, String>()
			ZipFile(scriptDex).use { zip ->
				listOf(
					"META-INF/dgminfo",
					"META-INF/services/org.codehaus.groovy.vmplugin.VMPlugin",
					"META-INF/services/org.codehaus.groovy.vmplugin.VMPluginFactory"
				).forEach { path ->
					val entry = zip.getEntry(path) ?: error("В $scriptDex нет $path")
					zip.getInputStream(entry).bufferedReader().use { r ->
						meta[path] = r.readText().trim()
					}
				}
			}

			// --- GroovySystem и VMPlugin ---
			dexLoader.loadClass("groovy.lang.GroovySystem")

			val vmPluginFactoryCls = dexLoader.loadClass("org.codehaus.groovy.vmplugin.VMPluginFactory")
			val java8PluginCls = dexLoader.loadClass("org.codehaus.groovy.vmplugin.v8.Java8")

			val pluginInstance = java8PluginCls.getDeclaredConstructor().newInstance()
			val pluginField = vmPluginFactoryCls.declaredFields.firstOrNull { f ->
				java8PluginCls.isAssignableFrom(f.type)
			} ?: throw IllegalStateException("Не найдено поле для VMPlugin в VMPluginFactory")

			pluginField.isAccessible = true
			pluginField.set(null, pluginInstance)


			// --- шаг 2. Загружаем твой скрипт ---
			val scriptCls = dexLoader.loadClass("ru.ravel.scripts.DateAdderScript")
			val ctor = scriptCls.getDeclaredConstructor().apply { isAccessible = true }
			val scriptObj = ctor.newInstance()

			// --- шаг 3. Передаём binding ---
			val bindingCls = dexLoader.loadClass("groovy.lang.Binding")
			val binding = bindingCls.getConstructor(Map::class.java)
				.newInstance(mapOf("input" to input))
			scriptCls.getMethod("setBinding", bindingCls).invoke(scriptObj, binding)

			// --- шаг 4. Вызываем run() ---
			val result = scriptCls.getMethod("run").invoke(scriptObj)
			@Suppress("UNCHECKED_CAST")
			return (result as? Map<String, Any?>)
				?: error("Скрипт вернул не Map, а ${result?.javaClass?.name}")
		} finally {
			Thread.currentThread().contextClassLoader = prevCL
		}
	}


	private fun wrapDexToJar(dexBytes: ByteArray, outJar: File) {
		java.util.zip.ZipOutputStream(outJar.outputStream()).use { zos ->
			zos.putNextEntry(java.util.zip.ZipEntry("classes.dex"))
			zos.write(dexBytes)
			zos.closeEntry()
		}
		outJar.outputStream().fd.sync()
	}


	private fun readDexDeclaredSize(ins: java.io.InputStream): Int {
		val header = ByteArray(0x70) // заголовок DEX 112 байт
		java.io.DataInputStream(ins).readFully(header)
		return java.nio.ByteBuffer.wrap(header, 0x20, 4)
			.order(java.nio.ByteOrder.LITTLE_ENDIAN).int
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


	suspend fun ensureDateAdderScript(url: String): File = withContext(Dispatchers.IO) {
		val dir = File(context.codeCacheDir, "groovy").apply { mkdirs() }
		val outFile = File(dir, "DateAdderScript-dex.jar")

		// Если файл уже есть и выглядит валидным — используем его
		if (outFile.exists() && verifyDexJarLight(outFile)) {
			hardenDexFile(outFile)
			return@withContext outFile
		}

		// 1) Скачиваем полностью на диск в *.part (одна попытка)
		val part = File(dir, "DateAdderScript-dex.jar.part").apply { delete() }
		downloadFileOnce(url, part)

		// 2) Если это raw .dex — упакуем в .jar (локально, без сети)
		if (isRawDex(part)) {
			val jarTmp = File(dir, "DateAdderScript-dex.jar.make")
			wrapDexToJar(part.readBytes(), jarTmp)
			replaceAtomically(jarTmp, part) // теперь part — уже jar
		}

		// 3) Сильная проверка целостности classes.dex внутри jar
		verifyDexJarStrong(part)

		// 4) Атомарно переносим в итоговый файл и ужесточаем права
		replaceAtomically(part, outFile)
		hardenDexFile(outFile)
		outFile
	}


	private fun downloadFileOnce(url: String, dest: File, maxAttempts: Int = 99) {
		if (dest.exists()) dest.delete()

		repeat(maxAttempts) { attempt ->
			try {
				val req = Request.Builder()
					.url(url)
					.header("Accept-Encoding", "identity")
					.header("Connection", "close")
					.build()

				httpClient.newCall(req).execute().use { resp ->
					require(resp.isSuccessful) { "HTTP ${resp.code}" }
					val body = resp.body ?: error("Empty body")
					val expected = resp.header("Content-Length")?.toLongOrNull()

					dest.sink().buffer().use { sink ->
						val source = body.source()
						var total = 0L
						while (true) {
							val read = source.read(sink.buffer, 256 * 1024L)
							if (read == -1L) break
							total += read
							sink.emitCompleteSegments()
						}
						sink.flush()

						if (expected != null && total != expected) {
							error("Размер не совпал: $total / $expected")
						}
						if (total == 0L) error("Файл пустой")
					}
				}
				// если дошли сюда — успех
				return
			} catch (e: javax.net.ssl.SSLProtocolException) {
				dest.delete()
				if (attempt == maxAttempts - 1) {
					throw IOException("Не удалось скачать за $maxAttempts попыток", e)
				}
				// небольшой бэк-офф, чтобы сервер успокоился
				Thread.sleep(1000)
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