package ru.ravel.ulwms.lcpe

import android.content.Context
import ru.ravel.lcpecore.runtime.GroovyExecutor
import ru.ravel.ulwms.service.ScriptLoader
import java.io.File

class AndroidGroovyExecutor(
	private val context: Context,
	private val scriptLoader: ScriptLoader,
	private val dexJar: File
) : GroovyExecutor {

	override fun exec(code: String, bindings: Map<String, Any?>, groovyClassName: String?): Any? {
		require(dexJar.exists()) { "Не найден DEX-JAR по пути: ${dexJar.absolutePath}" }
		val className = groovyClassName ?: return null
		val result = scriptLoader.loadInMemory(
			scriptDex = dexJar,
			input = bindings,
			className = className
		)
		if (result is Map<*, *> && bindings is MutableMap<*, *>) {
			@Suppress("UNCHECKED_CAST")
			(bindings as MutableMap<String, Any?>).putAll(result as Map<String, Any?>)
		}
		return result
	}

}