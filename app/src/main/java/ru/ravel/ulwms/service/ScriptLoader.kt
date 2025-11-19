package ru.ravel.ulwms.service

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File

class ScriptLoader(
	private val context: Context,
) {

	/**
	 *  Основной способ: InMemoryDexClassLoader (API 26+)
	 */
	@SuppressLint("DiscouragedPrivateApi")
	fun loadInMemory(
		scriptDex: File,
		input: Map<String, Any?>?,
		className: String
	): Map<String, Any?>? {
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
//			val ctor = scriptCls.getDeclaredConstructor().apply { isAccessible = true }
//			val scriptObj = ctor.newInstance()
			val method = scriptCls.getMethod("run", Map::class.java)
			val result = method.invoke(/*scriptObj*/null, input)
//			Log.i("DEBUG", result?.toString() ?: "")
			@Suppress("UNCHECKED_CAST")
			return (result as? Map<String, Any?>)
		} finally {
			Thread.currentThread().contextClassLoader = prevCL
		}
	}

}