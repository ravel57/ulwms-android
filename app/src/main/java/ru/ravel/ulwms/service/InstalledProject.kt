package ru.ravel.ulwms.service

import android.annotation.SuppressLint
import android.content.Context
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import ru.ravel.lcpeandroid.ProjectLoader
import ru.ravel.lcpecore.model.CoreProject
import ru.ravel.ulwms.utils.sha256
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
			val cleaned = raw.replace(
				Regex("[\\uFEFF\\u200B\\u200C\\u200D\\u200E\\u200F\\u202A-\\u202E\\u00A0]"),
				""
			)
			return cleaned.trim()
		}


		fun loadLastInstalled(context: Context): InstalledProject {
			val projectsDir = File(context.cacheDir, "projects").apply { mkdirs() }
			return loadLastInstalledFromDisk(context, projectsDir)
		}


		private fun loadLastInstalledFromDisk(context: Context, projectsDir: File): InstalledProject {
			// Ищем директории проектов
			val dirs = projectsDir
				.listFiles { f -> f.isDirectory }
				?.toList()
				.orEmpty()

			require(dirs.isNotEmpty()) {
				"Нет ни одного установленного сценария, а API вернул пустой ответ"
			}

			// Берём самый свежий по времени изменения
			val latestDir = dirs.maxByOrNull { it.lastModified() }!!
			val projectName = latestDir.name

			// Находим project.json (либо <name>.json, либо первый .json в папке)
			val projectJson = File(latestDir, "$projectName.json")
				.takeIf { it.exists() }
				?: latestDir.listFiles()
					?.firstOrNull { it.isFile && it.extension.equals("json", true) }
				?: error("В каталоге $latestDir нет json-файла проекта")

			val project = ProjectLoader.loadProjectFromFile(projectJson)
			project.baseDir = latestDir
			project.blocks.forEach { b ->
				b.codePath = normalizeToAbsSmart(latestDir, b.codePath)
				b.subProjectPath = normalizeToAbsSmart(latestDir, b.subProjectPath) ?: ""
			}

			// Ищем groovy-blocks-dex.jar: сперва в codeCache, затем в самом проекте
			val groovyRoot = File(context.codeCacheDir, "groovy")

			val projectDexDirs = groovyRoot.listFiles()
				?.filter { it.isDirectory && it.name.startsWith("$projectName-") }
				.orEmpty()

			val latestDexDir = projectDexDirs.maxByOrNull { it.lastModified() }
			val dexFromCache = latestDexDir
				?.let { File(it, "groovy-blocks-dex.jar") }

			val dexFromProject = File(latestDir, "groovy-blocks-dex.jar")

			val dexJar = when {
				dexFromCache?.exists() == true -> dexFromCache
				dexFromProject.exists() -> dexFromProject
				else -> error("Не найден groovy-blocks-dex.jar для проекта $projectName")
			}

			return InstalledProject(
				baseDir = latestDir,
				projectJson = projectJson,
				dexJar = dexJar,
				project = project
			)
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
			val looksAndroidAbs =
				s.startsWith("/data/") || s.startsWith("/storage/") || s.startsWith("/sdcard/")
			if (s.startsWith("/") && !looksAndroidAbs) {
				s = s.removePrefix("/")
			}
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
					if (zipFile.length() == 0L) {
						return@map loadLastInstalledFromDisk(context, projectsDir)
					}

					val projectName = zipFile.nameWithoutExtension
					val projectDir = File(projectsDir, projectName)
					if (projectDir.exists()) projectDir.deleteRecursively()
					require(projectDir.mkdirs()) { "Не удалось создать каталог проекта $projectDir" }

					unzipTo(zipFile, projectDir)

					val projectJson = File(projectDir, "${projectName}.json")
					require(projectJson.exists()) { "Не найден ${projectName}.json" }

					val dexJarSrc = File(projectDir, "groovy-blocks-dex.jar")
					require(dexJarSrc.exists()) { "Не найден groovy-blocks-dex.jar" }

					// версию берём из самого dex/zip
					val versionHash = dexJarSrc.sha256().take(8)

					// подчистим старые версии этого проекта в codeCache, чтобы не плодить мусор
					val groovyRoot = File(context.codeCacheDir, "groovy")
					groovyRoot.listFiles()
					?.filter { it.isDirectory && it.name.startsWith("$projectName-") }
					?.forEach { it.deleteRecursively() }

					// кладём dex в уникальную папку вида groovy/<projectName>-<hash>/
					val dexCacheDir = File(groovyRoot, "$projectName-$versionHash").apply { mkdirs() }
					val dexCacheFile = File(dexCacheDir, "groovy-blocks-dex.jar")
					dexJarSrc.inputStream().use { input ->
						dexCacheFile.outputStream().use { output -> input.copyTo(output) }
					}
					dexCacheFile.setReadable(true, false)
					dexCacheFile.setWritable(false, false)
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

		private fun downloadWithRetries(
			url: String,
			dest: File,
			maxAttempts: Int = 99
		): Single<File> {
			return Single.defer {
				RetrofitProvider.api.downloadFile(url)
					.map { response ->
						val body = response.body()
						if (body == null) {
							dest.parentFile?.mkdirs()
							dest.delete()
							dest.createNewFile()
							return@map dest
						}

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
							tmp.outputStream().use { out -> body?.byteStream()?.copyTo(out) }
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


		fun getAllProjectFiles(context: Context): List<File> {
			val projectsDir = File(context.cacheDir, "projects")
			if (!projectsDir.exists()) {
				return emptyList()
			}
			return projectsDir
				.walkTopDown()
				.filter { it.isFile }
				.toList()
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
