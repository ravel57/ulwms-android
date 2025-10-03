package ru.ravel.ulwms.service

import android.annotation.SuppressLint
import android.content.Context
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import ru.ravel.lcpeandroid.ProjectLoader
import ru.ravel.lcpecore.model.CoreProject
import java.io.File
import java.util.zip.ZipFile
import javax.net.ssl.SSLProtocolException

/**
 * Результат установки проекта
 */
data class InstalledProject(
	val baseDir: File,
	val projectJson: File,
	val dexJar: File,
	val project: CoreProject
) {

	companion object {

		private fun sanitizePath(raw: String?): String {
			if (raw == null) return ""
			val cleaned = raw.replace(Regex("[\\uFEFF\\u200B\\u200C\\u200D\\u200E\\u200F\\u202A-\\u202E\\u00A0]"), "")
			return cleaned.trim()
		}


		private fun normalizeToAbsSmart(base: File, raw: String?): String? {
			val s0 = sanitizePath(raw)
			if (s0.isEmpty()) {
				return null
			}
			var s = s0.replace('\\', '/')
				.removePrefix("file://")
				.removePrefix("./")
			s = s.replace(Regex("^[A-Za-z]:/"), "")
			val looksAndroidAbs = s.startsWith("/data/") || s.startsWith("/storage/") || s.startsWith("/sdcard/")
			if (s.startsWith("/") && !looksAndroidAbs) s = s.removePrefix("/")
			val f = File(s)
			val abs = (if (f.isAbsolute) f else File(base, s)).normalize().absolutePath
			return abs
		}


		@SuppressLint("SetWorldReadable")
		fun install(context: Context, url: String): Single<InstalledProject> {
			val projectsDir = File(context.cacheDir, "projects").apply { mkdirs() }
			val tmpFile = File(projectsDir, "tmp.part").apply { delete() }

			return downloadWithRetries(url, tmpFile)
				.map { zipFile ->
					val projectName = zipFile.nameWithoutExtension
					val projectDir = File(projectsDir, projectName)
					if (projectDir.exists()) projectDir.deleteRecursively()
					projectDir.mkdirs()

					unzipTo(zipFile, projectDir)

					val projectJson = File(projectDir, "${projectName}.json")
					require(projectJson.exists()) { "Не найден ${projectName}.json" }

					val dexJarSrc = File(projectDir, "groovy-blocks-dex.jar")
					require(dexJarSrc.exists()) { "Не найден groovy-blocks-dex.jar" }

					val dexCacheDir = File(context.codeCacheDir, "groovy/$projectName").apply { mkdirs() }
					val dexCacheFile = File(dexCacheDir, "groovy-blocks-dex.jar")
					if (!dexCacheFile.exists()) {
						dexJarSrc.inputStream().use { input ->
							dexCacheFile.outputStream().use { output -> input.copyTo(output) }
						}
						dexCacheFile.setReadable(true, false)
						dexCacheFile.setWritable(false, false) // читаем из кэша, не перезаписываем на девайсе
					}

					val project = ProjectLoader.loadProjectFromFile(projectJson)
					project.baseDir = projectDir


					project.blocks.forEach { b ->
						b.codePath = normalizeToAbsSmart(projectDir, b.codePath)
						b.subProjectPath = normalizeToAbsSmart(projectDir, b.subProjectPath) ?: ""
					}
					InstalledProject(
						baseDir = projectDir,
						projectJson = projectJson,
						dexJar = dexCacheFile,
						project = project
					)
				}
		}

		private fun downloadWithRetries(url: String, dest: File, maxAttempts: Int = 99): Single<File> {
			return Single.defer {
				RetrofitProvider.api.downloadFile(url)
					.map { response ->
						val body = response.body() ?: error("Пустой ответ")
						body.use {
							val cd = response.headers()["Content-Disposition"]
							val realName = cd
								?.substringAfter("filename=", "")
								?.trim('"')
								?.takeIf { it.isNotEmpty() }
								?: dest.name
							val outFile = File(dest.parentFile, realName)
							val tmp = File(outFile.parentFile, "${outFile.name}.part")
								.apply { delete() }
							tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
							if (outFile.exists()) {
								outFile.delete()
							}
							require(tmp.renameTo(outFile)) {
								"Не удалось переименовать ${tmp.name} → ${outFile.name}"
							}
							outFile
						}
					}
			}.retryWhen { errors ->
				errors.zipWith(Flowable.range(1, maxAttempts)) { e, attempt ->
					if (e is SSLProtocolException && attempt < maxAttempts) {
						RetrofitProvider.resetClient()
						Thread.sleep(1000)
						attempt
					} else throw e
				}
			}
		}


		private fun unzipTo(zip: File, destDir: File) {
			ZipFile(zip).use { zipFile ->
				zipFile.entries().asSequence().forEach { entry ->
					val outFile = File(destDir, entry.name)
					if (entry.isDirectory) {
						outFile.mkdirs()
					} else {
						outFile.parentFile?.mkdirs()
						zipFile.getInputStream(entry).use { input ->
							outFile.outputStream().use { output ->
								input.copyTo(output)
							}
						}
					}
				}
			}
		}
	}


	private fun resolveSubFile(outerBaseDir: File?, raw: String): Pair<File?, List<String>> {
		val base = outerBaseDir ?: File(".")
		val tried = mutableListOf<String>()
		val p = raw.replace('\\', '/').trim()
		fun tryFile(f: File): File? {
			val abs = f.normalize().absoluteFile
			tried += abs.path
			return abs.takeIf { it.exists() }
		}
		File(p).takeIf { it.isAbsolute }?.let { tryFile(it) }?.let { return it to tried }
		tryFile(File(base, p))?.let { return it to tried }
		if (!p.endsWith(".json", ignoreCase = true)) {
			tryFile(File(base, "$p.json"))?.let { return it to tried }
		}
		val withStd = if (p.startsWith("stdlib/")) p else "stdlib/$p"
		tryFile(File(base, withStd))?.let { return it to tried }
		if (!withStd.endsWith(".json", ignoreCase = true)) {
			tryFile(File(base, "$withStd.json"))?.let { return it to tried }
		}
		return null to tried
	}

}
