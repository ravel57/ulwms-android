package ru.ravel.ulwms

import android.annotation.SuppressLint
import android.content.Context
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.TlsVersion
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class ScriptLoader(
	private val context: Context,
) {

	private val GROOVY_ALL_DEX = "groovy-runtime-all.dex.jar"
	private val httpClient: OkHttpClient by lazy {
		val spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
			.tlsVersions(TlsVersion.TLS_1_2) // отключаем TLS 1.3
			.allEnabledCipherSuites()
			.build()

		OkHttpClient.Builder()
			.protocols(listOf(Protocol.HTTP_1_1))
			.connectionSpecs(listOf(spec, ConnectionSpec.CLEARTEXT))
			.retryOnConnectionFailure(true)
			.connectTimeout(20, TimeUnit.SECONDS)
			.readTimeout(60, TimeUnit.SECONDS)
			.callTimeout(90, TimeUnit.SECONDS)
			.build()
	}

	/**
	 * Если сервер отдаёт dex.jar — достаём из него classes.dex
	 */
	suspend fun downloadDexFromJar(url: String): ByteArray = withContext(Dispatchers.IO) {
		val maxAttempts = 5
		var attempt = 0

		while (attempt < maxAttempts) {
			attempt++

			val req = Request.Builder()
				.url(url)
				.header("Accept-Encoding", "identity")
				.header("Connection", "close")
				.build()

			val resp = try {
				httpClient.newCall(req).execute()
			} catch (e: IOException) {
				if (attempt >= maxAttempts) throw e else continue
			}

			resp.use { r ->
				if (!r.isSuccessful) {
					if (attempt >= maxAttempts) error("HTTP ${r.code}") else return@use
				}
				val body = r.body ?: error("Empty body")

				try {
					body.byteStream().use { stream ->
						ZipInputStream(stream.buffered()).use { zis ->
							var entry = zis.nextEntry
							val out = java.io.ByteArrayOutputStream()
							while (entry != null) {
								if (!entry.isDirectory && entry.name == "classes.dex") {
									val buf = ByteArray(256 * 1024)
									while (true) {
										val read = try {
											zis.read(buf)
										} catch (e: javax.net.ssl.SSLProtocolException) {
											// оборвало соединение → попробуем заново
											break
										}
										if (read == -1) break
										out.write(buf, 0, read)
									}
									if (out.size() > 0) {
										return@withContext out.toByteArray()
									} else {
										if (attempt >= maxAttempts) error("Не удалось дочитать classes.dex")
										else break // retry
									}
								}
								entry = zis.nextEntry
							}
						}
					}
				} catch (e: javax.net.ssl.SSLProtocolException) {
					if (attempt >= maxAttempts) throw e else continue
				}
			}
		}
		error("В dex.jar не найден classes.dex")
	}


	/**
	 *  Основной способ: InMemoryDexClassLoader (API 26+)
	 */
	fun loadInMemory(
		scriptDex: File,
		input: Map<String, Any?>,
		parent: DexClassLoader,
	): Map<String, Any?> {
		val dir = File(context.codeCacheDir, "groovy").apply { mkdirs() }
		val runtimeDex = File(dir, "groovy-runtime-all.dex.jar")
		require(runtimeDex.exists()) { "Нет ${runtimeDex.absolutePath}" }
		require(scriptDex.exists()) { "Нет ${scriptDex.absolutePath}" }

		// Подгружаем GroovySystem чтобы подтянулся runtime
		parent.loadClass("groovy.lang.GroovySystem")

		val clazz = parent.loadClass("ru.ravel.scripts.DateAdderScript")
		val scriptObj = clazz.getDeclaredConstructor().newInstance()

		val bindingClass = Class.forName("groovy.lang.Binding", false, parent)
		val binding = bindingClass.getConstructor(Map::class.java)
			.newInstance(mapOf("input" to input))
		clazz.getMethod("setBinding", bindingClass).invoke(scriptObj, binding)

		val result = clazz.getMethod("run").invoke(scriptObj)

		@Suppress("UNCHECKED_CAST")
		return (result as? Map<String, Any?>)
			?: error("Скрипт вернул не Map, а ${result?.javaClass?.name}")
	}

	suspend fun ensureGroovyRuntime(urlAll: String): File =
		withContext(Dispatchers.IO) {
			val dir = File(context.codeCacheDir, "groovy").apply { mkdirs() }
			val allJar = File(dir, "groovy-runtime-all.dex.jar")

			if (!allJar.exists()) {
				val tmp = File(dir, "groovy-runtime-all.part")
				var attempt = 0
				while (true) {
					attempt++
					try {
						downloadToFile(urlAll, tmp)

						// если пришёл "сырой" classes.dex → обернём в jar
						if (isRawDex(tmp.readBytes())) {
							wrapDexToJar(tmp.readBytes(), tmp)
						}

						verifyDexJarStrong(tmp)
						makeReadOnlyCopy(tmp, allJar)
						break
					} catch (e: Throwable) {
						tmp.delete()
						if (attempt >= 3) throw e
					}
				}
			}
			allJar
		}


	// Скачивание «как байты» (без распаковки как zip)
	private fun downloadBytes(urlStr: String): ByteArray {
		val req = Request.Builder()
			.url(urlStr)
			.header("Accept-Encoding", "identity")
			.header("Connection", "close")
			.build()
		httpClient.newCall(req).execute().use { r ->
			if (!r.isSuccessful) error("HTTP ${r.code}")
			return r.body.bytes()
		}
	}

	private fun verifyDexJar(file: File) {
		ZipFile(file).use { z ->
			require(z.getEntry("classes.dex") != null) { "В ${file.name} нет classes.dex" }
		}
	}

	private fun verifyHasMetaInf(file: File) {
		ZipFile(file).use { z ->
			val hasServices = z.getEntry("META-INF/services/org.codehaus.groovy.vmplugin.VMPlugin") != null ||
					z.getEntry("META-INF/services/org.codehaus.groovy.vmplugin.VMPluginFactory") != null
			require(hasServices) { "В ${file.name} нет META-INF/services для VMPlugin" }
		}
	}

	@SuppressLint("SetWorldReadable")
	private fun makeReadOnly(file: File) {
		file.setReadable(true, false)
		file.setWritable(false, false)
		file.setExecutable(false, false)
	}

	@SuppressLint("SetWorldReadable")
	private fun makeReadOnlyCopy(src: File, dst: File) {
		src.copyTo(dst, overwrite = true)
		dst.setReadable(true, false)
		dst.setWritable(false, false)
		dst.setExecutable(false, false)
	}


	private fun downloadToFile(urlStr: String, outFile: File) {
		val tmp = File(outFile.parentFile, outFile.name + ".part")
		var downloaded = if (tmp.exists()) tmp.length() else 0L
		var attempt = 0
		val maxAttempts = 5

		while (attempt < maxAttempts) {
			attempt++

			val reqBuilder = Request.Builder()
				.url(urlStr)
				.header("Accept-Encoding", "identity")
				.header("Connection", "close")

			if (downloaded > 0L) {
				reqBuilder.header("Range", "bytes=$downloaded-")
			}

			val call = httpClient.newCall(reqBuilder.build())
			val resp = try {
				call.execute()
			} catch (e: IOException) {
				if (attempt >= maxAttempts) throw e else continue
			}

			var completed = false
			resp.use { r ->
				if (!r.isSuccessful && r.code !in listOf(206, 200)) {
					if (attempt >= maxAttempts) error("HTTP ${r.code}") else return@use
				}

				// Если сервер вернул полный файл при Range — начнём заново
				if (r.code == 200 && downloaded > 0L) {
					tmp.delete()
					downloaded = 0L
				}

				val body = r.body ?: error("Empty body")
				val expected = r.headers["Content-Length"]?.toLongOrNull()

				body.byteStream().use { input ->
					tmp.outputStream().use { output ->
						val buf = ByteArray(256 * 1024)
						var total = downloaded
						while (true) {
							val read = try {
								input.read(buf)
							} catch (e: javax.net.ssl.SSLProtocolException) {
								break // оборвало соединение
							}
							if (read == -1) break
							output.write(buf, 0, read)
							total += read
						}
						// Проверяем, дочитали ли всё
						if (expected == null || total - downloaded >= expected) {
							downloaded = total
							completed = true
						} else {
							downloaded = total
						}
					}
				}
			}

			if (completed) break
			// иначе пойдём на новую попытку с Range
		}

		val finalSize = tmp.length()
		if (finalSize == 0L) error("Файл пустой")
		if (outFile.exists()) outFile.delete()
		if (!tmp.renameTo(outFile)) error("Не удалось переименовать временный файл")
	}

	private fun isRawDex(bytes: ByteArray): Boolean =
		bytes.size > 8 && bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() &&
				bytes[2] == 'x'.code.toByte() && bytes[3] == '\n'.code.toByte()

	private fun wrapDexToJar(dexBytes: ByteArray, outJar: File) {
		java.util.zip.ZipOutputStream(outJar.outputStream()).use { zos ->
			zos.putNextEntry(java.util.zip.ZipEntry("classes.dex"))
			zos.write(dexBytes)
			zos.closeEntry()
		}
	}

	private fun readDexDeclaredSize(ins: java.io.InputStream): Int {
		val header = ByteArray(0x70) // заголовок DEX 112 байт
		java.io.DataInputStream(ins).readFully(header)
		return java.nio.ByteBuffer.wrap(header, 0x20, 4)
			.order(java.nio.ByteOrder.LITTLE_ENDIAN).int
	}

	private fun downloadToExact(url: String, out: File) {
		val req = okhttp3.Request.Builder()
			.url(url)
			.header("Accept-Encoding", "identity") // без gzip
			.build()
		httpClient.newCall(req).execute().use { r ->
			if (!r.isSuccessful) error("HTTP ${r.code} при загрузке $url")
			val expected = r.body?.contentLength() ?: -1
			out.outputStream().use { dst -> r.body!!.byteStream().copyTo(dst) }
			if (expected > 0 && out.length() != expected) {
				error("Получено ${out.length()} из $expected байт ($url)")
			}
		}
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

		if (!outFile.exists()) {
			val tmp = File(dir, "DateAdderScript-dex.part")
			var attempt = 0
			while (true) {
				attempt++
				try {
					downloadToFile(url, tmp)

					// если пришёл «сырой» classes.dex → обернём в jar
					if (isRawDex(tmp.readBytes())) {
						wrapDexToJar(tmp.readBytes(), tmp)
					}

					verifyDexJarStrong(tmp)
					makeReadOnlyCopy(tmp, outFile)
					break
				} catch (e: Throwable) {
					tmp.delete()
					if (attempt >= 3) throw e
				}
			}
		}
		outFile
	}
}