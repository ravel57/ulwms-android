package ru.ravel.ulwms.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.preference.PreferenceManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.ravel.lcpeandroid.AndroidOutputsRepository
import ru.ravel.lcpeandroid.AndroidProjectRepository
import ru.ravel.lcpeandroid.AndroidRunController
import ru.ravel.lcpecore.model.CoreBlock
import ru.ravel.lcpecore.model.CoreProject
import ru.ravel.ulwms.R
import ru.ravel.ulwms.databinding.ActivityMainBinding
import ru.ravel.ulwms.dto.SharedViewModel
import ru.ravel.ulwms.fragment.SettingsFragment
import ru.ravel.ulwms.lcpe.AndroidGroovyExecutor
import ru.ravel.ulwms.service.HttpClient
import ru.ravel.ulwms.service.InstalledProject
import ru.ravel.ulwms.service.ScriptLoader
import ru.ravel.ulwms.service.UpdateCheckWorker
import ru.ravel.ulwms.utils.JsonUiRenderer
import java.lang.Byte
import java.lang.Short
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.Any
import kotlin.Boolean
import kotlin.Char
import kotlin.CharSequence
import kotlin.Double
import kotlin.Enum
import kotlin.Exception
import kotlin.Float
import kotlin.Int
import kotlin.Long
import kotlin.Number
import kotlin.String
import kotlin.Suppress
import kotlin.Throwable
import kotlin.Unit
import kotlin.apply
import kotlin.getValue
import kotlin.let
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaType
import kotlin.runCatching
import kotlin.takeIf


class MainActivity : AppCompatActivity() {

	private lateinit var appBarConfiguration: AppBarConfiguration
	private lateinit var binding: ActivityMainBinding
	private val viewModel: SharedViewModel by viewModels()

	private val scope = MainScope()
	private lateinit var controller: AndroidRunController
	private lateinit var blockId: UUID
	private lateinit var homeView: View
	@Volatile
	private var runId: Long = 0L

	private lateinit var installedProject: InstalledProject
	private lateinit var scriptLoader: ScriptLoader
	private var currentFormBlock: CoreBlock? = null
	private var currentFormInitial: Map<String, Any?> = emptyMap()
	private var projectCompleted = false


	@SuppressLint("CheckResult")
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		instance = this

		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)

		val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
		bottomNav.setOnItemSelectedListener { item ->
			when (item.itemId) {
				R.id.nav_home -> {
					showHomeScreen()
					true
				}

//				R.id.nav_tasks -> {
//					showTasksScreen()
//					true
//				}

				R.id.nav_settings -> {
					showSettingsScreen()
					true
				}

				else -> false
			}
		}

		binding.fab.setOnClickListener {
			val intent = Intent(this, ScannerActivity::class.java)
			startActivityForResult(intent, 1001)
		}

		runUpdateCheckOnce()
		startUpdateScheduler()
		loadScenario()
	}


	fun reloadScenario() {
		projectCompleted = false
		currentFormBlock = null
		currentFormInitial = emptyMap()
		loadScenario()
	}


	private fun loadScenario() {
		lifecycleScope.launch {
			try {
				val installed = withContext(Dispatchers.IO) {
					InstalledProject.loadLastInstalled(this@MainActivity)
				}
				lowCodeLogic(installed)
			} catch (e: Exception) {
				Log.e("MainActivity", "Ошибка загрузки сценария", e)
			}
		}
	}


	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		super.onActivityResult(requestCode, resultCode, data)

		if (requestCode == 1001 && resultCode == RESULT_OK) {
			val qr = data?.getStringExtra("qr")?.trim()
				?: return
			viewModel.instruction.value = qr
			onCameraClick(qr)
		}
	}


	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.menu_main, menu)
		return true
	}


	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_settings -> true
			else -> super.onOptionsItemSelected(item)
		}
	}


	override fun onSupportNavigateUp(): Boolean {
		val navController = findNavController(R.id.nav_host_fragment_content_main)
		return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
	}


	@SuppressLint("CheckResult")
	private fun lowCodeLogic(installed: InstalledProject) {
		projectCompleted = false
		currentFormBlock = null
		currentFormInitial = emptyMap()
		installedProject = installed   // важно для Groovy

		// запоминаем "поколение" этого запуска
		val myRunId = ++runId

		lifecycleScope.launch(Dispatchers.Main) {
			// Создаем controller в UI-потоке
			val repo = AndroidProjectRepository(this@MainActivity)
			val outputsRepo = AndroidOutputsRepository()
			scriptLoader = ScriptLoader(this@MainActivity)
			val groovyExecutor = AndroidGroovyExecutor(
				this@MainActivity,
				scriptLoader,
				installed.dexJar
			)

			val localController = AndroidRunController(
				context = this@MainActivity,
				projectRepo = repo,
				outputsRepo = outputsRepo,
				groovy = groovyExecutor,
				python = null,
				js = null
			)
			controller = localController

			// true, если колбэк пришёл от устаревшего запуска
			fun isStale(): Boolean {
				return (myRunId != runId) || (controller !== localController)
			}

			localController.runAsync(
				installed.project,
				installed.projectJson,
				object : AndroidRunController.RunEvents {

					override fun onStart(block: CoreBlock) {
						if (isStale()) {
							return
						}
					}

					override fun onOutput(
						block: CoreBlock,
						payload: Map<String, Any?>
					) {
						if (isStale()) {
							return
						}
						Log.d("OUTPUT", "${block.name}: $payload")
					}

					override fun onFinish(block: CoreBlock) {
						if (isStale()) {
							return
						}
					}

					override fun onError(block: CoreBlock, error: Throwable) {
						if (isStale()) {
							return
						}
						Log.e(
							"MainActivity",
							"❌ Ошибка в блоке ${block.name}: ${error.message}",
							error
						)
					}

					override fun onCompleted(project: CoreProject) {
						if (isStale()) {
							return
						}
						Log.i("MainActivity", "Проект отработал все что было")
						projectCompleted = true
						runOnUiThread {
							val view = TextView(this@MainActivity).apply {
								text = "Задач нет"
								textSize = 22f
								setPadding(40, 40, 40, 40)
							}
							val container = findViewById<FrameLayout>(binding.container.id)
							container.removeAllViews()
							homeView = view
							container.addView(view)
						}
					}

					@SuppressLint("SetTextI18n")
					override fun onFormRequested(
						block: CoreBlock,
						specJson: String,
						initial: Map<String, Any?>
					) {
						if (isStale()) {
							return
						}
						if (projectCompleted) {
							return
						}

						blockId = block.id
						currentFormBlock = block
						currentFormInitial = initial
						runOnUiThread {
							val root = layoutByJson(specJson)
							val viewTypes = buildViewTypesIndex(specJson)
							applyBindingsFromInitial(root, initial, viewTypes)
						}
					}
				}
			)
		}
	}


	private fun buildViewTypesIndex(specJson: String): Map<String, Class<out View>> {
		val root = JSONObject(specJson)
		val result = mutableMapOf<String, Class<out View>>()

		fun visit(obj: JSONObject) {
			val id = obj.optString("id", "")
			val type = obj.optString("type", "")
			if (id.isNotBlank() && type.isNotBlank()) {
				result[id] = JsonUiRenderer.getTypeByTag(this@MainActivity, obj)
			}
			val children = obj.optJSONArray("children")
			if (children != null) {
				for (i in 0 until children.length()) {
					val child = children.optJSONObject(i) ?: continue
					visit(child)
				}
			}
		}

		visit(root)
		return result
	}


	private fun applyBindingsFromInitial(
		root: View,
		initial: Map<String, Any?>,
		viewTypes: Map<String, Class<out View>>
	) {
		@Suppress("UNCHECKED_CAST")
		val bindings = when (val raw = initial["bindings"]) {
			is List<*> -> raw.filterIsInstance<Map<String, Any?>>()
			is Map<*, *> -> {
				val inner = raw["value"]
				(inner as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()
			}

			else -> emptyList()
		}
		bindings.forEach { b ->
			val tag = b["tag"] as? String ?: return@forEach
			val property = b["property"] as? String ?: return@forEach
			val rawValue = b["value"]

			val view = root.findViewWithTag<View>(tag) ?: return@forEach
			val viewType = viewTypes[tag]      // Class<out View>?

			applyPropertyReflectively(view, viewType, property, rawValue)
		}
	}


	private fun applyPropertyReflectively(
		view: View,
		viewType: Class<out View>?,
		property: String,
		rawValue: Any?
	) {
		if (rawValue == null) {
			return
		}
		val value = normalizeValue(rawValue)
		val target: Any = viewType?.takeIf { it.isInstance(view) }?.cast(view) ?: view
		runCatching {
			val kProp = target::class.memberProperties
				.firstOrNull { it.name.equals(property, ignoreCase = true) }
					as? KMutableProperty1<Any, Any?>

			if (kProp != null) {
				val paramClass = (kProp.returnType.javaType as? Class<*>)
				val coerced = coerceToType(view, value, paramClass, property)
				if (isParamCompatible(paramClass, coerced)) {
					kProp.set(target, coerced)
					return
				}
			}
		}
		runCatching {
			val candidates = listOf(
				property,
				"set" + property.replaceFirstChar { it.uppercase() }
			)
			val methods = target::class.java.methods.filter { m ->
				m.parameterTypes.size == 1 && candidates.any {
					it.equals(m.name, ignoreCase = true)
				}
			}
			for (m in methods) {
				val paramClass = m.parameterTypes[0]
				val coerced = coerceToType(view, value, paramClass, property)
				if (!isParamCompatible(paramClass, coerced)) {
					continue
				}
				m.invoke(target, coerced)
				return
			}
		}
	}

	/** Проверяем, совместим ли аргумент с типом параметра, чтобы не выстрелить IllegalArgumentException */
	private fun isParamCompatible(paramType: Class<*>?, value: Any?): Boolean {
		if (paramType == null) {
			return true
		}
		if (value == null) {
			return !paramType.isPrimitive
		}
		if (paramType.isInstance(value)) {
			return true
		}
		if (paramType.isPrimitive) {
			return when (paramType) {
				java.lang.Boolean.TYPE -> value is Boolean

				Integer.TYPE,
				Short.TYPE,
				Byte.TYPE,
				Character.TYPE,
				java.lang.Long.TYPE,
				java.lang.Float.TYPE,
				java.lang.Double.TYPE -> value is Number || value is Char

				else -> false
			}
		}
		return false
	}


	/**
	 * Универсальное приведение значения к нужному типу параметра/свойства.
	 * Никаких привязок к конкретным классам View.
	 */
	private fun coerceToType(
		view: View,
		raw: Any,
		targetType: Class<*>?,
		property: String
	): Any {
		if (targetType == null) {
			return raw
		}
		if (targetType.isInstance(raw)) {
			return raw
		}
		// строки
		if (raw is String) {
			val s = raw.trim()
			// boolean
			if (targetType == Boolean::class.java || targetType == java.lang.Boolean::class.java) {
				return s.toBooleanStrictOrNull() ?: (s.equals("1"))
			}
			// int
			if (targetType == Int::class.java || targetType == Integer::class.java) {
				// спец-случай visibility
				if (property.equals("visibility", ignoreCase = true)) {
					return when (s.lowercase()) {
						"gone" -> View.GONE
						"invisible" -> View.INVISIBLE
						else -> View.VISIBLE
					}
				}
				// спец-случай src: имя ресурса -> id
				if (property.equals("src", ignoreCase = true)) {
					val resId = view.context.resources.getIdentifier(
						s, "drawable", view.context.packageName
					)
					if (resId != 0) {
						return resId
					}
				}
				s.toIntOrNull()?.let { return it }
			}
			// float / double / long
			if (targetType == Float::class.java || targetType == java.lang.Float::class.java) {
				s.toFloatOrNull()?.let { return it }
			}
			if (targetType == Double::class.java || targetType == java.lang.Double::class.java) {
				s.toDoubleOrNull()?.let { return it }
			}
			if (targetType == Long::class.java || targetType == java.lang.Long::class.java) {
				s.toLongOrNull()?.let { return it }
			}
			// CharSequence / String
			if (targetType.isAssignableFrom(String::class.java) ||
				targetType.isAssignableFrom(CharSequence::class.java)
			) {
				return s
			}
			// enum
			if (targetType.isEnum) {
				val constant = targetType.enumConstants
					?.firstOrNull { (it as Enum<*>).name.equals(s, ignoreCase = true) }
				if (constant != null) {
					return constant
				}
			}
		}
		if (raw is Number) {
			when (targetType) {
				Int::class.java, Integer::class.java -> return raw.toInt()
				Float::class.java, java.lang.Float::class.java -> return raw.toFloat()
				Double::class.java, java.lang.Double::class.java -> return raw.toDouble()
				Long::class.java, java.lang.Long::class.java -> return raw.toLong()
			}
		}
		return raw
	}


	private fun normalizeValue(raw: Any): Any {
		return when (raw) {
			is Boolean, is Number -> raw

			is String -> {
				val v = raw.trim()
				// boolean
				v.toBooleanStrictOrNull()?.let { return it }
				// int
				v.toIntOrNull()?.let { return it }
				// float
				v.toFloatOrNull()?.let { return it }
				// Цвета: #RGB / #ARGB / #RRGGBB / #AARRGGBB
				if (v.startsWith("#")) {
					try {
						return Color.parseColor(v)
					} catch (_: Exception) {
					}
				}
				// Именованные цвета (android.graphics.Color)
				try {
					val colorField = Color::class.java.getField(v)
					val colorInt = colorField.get(null)
					if (colorInt is Int) {
						return colorInt
					}
				} catch (_: Exception) {
				}
				// ---- visibility ----
				when (v.lowercase()) {
					"visible" -> return View.VISIBLE
					"invisible" -> return View.INVISIBLE
					"gone" -> return View.GONE
				}
				v
			}

			else -> raw
		}
	}


	@SuppressLint("CheckResult")
	private fun onCameraClick(value: String = "") {
		val prefs = PreferenceManager.getDefaultSharedPreferences(this)
		val host = prefs.getString("server_url", getString(R.string.api_url))!!
		val userId = prefs.getString("user_id", "0")?.toLong()!!
		HttpClient().sendScan(host, value, userId)
			.observeOn(AndroidSchedulers.mainThread())
			.subscribe(
				{ response -> viewModel.instruction.value = response.message },
				{ e -> e.printStackTrace() }
			)
	}


	private fun collectActions(json: JSONObject, acc: MutableMap<String, () -> Unit>) {
		val type = json.optString("type", "")
		if (type == "SubmitButton" && json.has("submitAction")) {
			val submit = json.getString("submitAction")
			if (submit.isNotBlank()) {
				acc[submit] = { submitFromGroovy(submit) }
			}
		}
		if (type == "Button" && json.has("action")) {
			val action = json.getString("action")
			if (action.isNotBlank()) {
				acc[action] = { runGroovyAction(action) }
			}
		}
		val children = json.optJSONArray("children")
		if (children != null) {
			for (i in 0 until children.length()) {
				val child = children.optJSONObject(i)
				if (child != null) collectActions(child, acc)
			}
		}
	}


	private fun layoutByJson(jsonStr: String): View {
		val spec = JSONObject(jsonStr)
		val actions = mutableMapOf<String, () -> Unit>()
		collectActions(spec, actions)
		val view = JsonUiRenderer.render(this, spec, actions)
		homeView = view
		val container = findViewById<FrameLayout>(R.id.container)
		container.removeAllViews()
		container.addView(view)
		return view
	}


	@Suppress("UNCHECKED_CAST")
	private fun submitFromGroovy(action: String) {
		val block = currentFormBlock ?: return
		// Ищем PostProcessingNode для этого action
		val postNode = block.postProcessingNodes
			.firstOrNull { it.action == action }
		val className = postNode
			?.submitDataClassName
			?.takeIf { it.isNotBlank() }
		val payload: Map<String, Any?> = if (className != null) {
			val result = scriptLoader.loadInMemory(
				installedProject.dexJar,
				currentFormInitial,
				className
			) as? Map<String, Any?>
			result ?: emptyMap()
		} else {
			// fallback если groovy отсутствует
			emptyMap()
		}
		onSubmit(block.id, payload)
	}


	@Suppress("UNCHECKED_CAST")
	private fun runGroovyAction(action: String) {
		val block = currentFormBlock ?: return

		val postNode = block.postProcessingNodes
			.firstOrNull { it.action == action }

		val className = postNode
			?.mainProcessingClassName
			?.takeIf { it.isNotBlank() }

		if (className != null) {
			val result = scriptLoader.loadInMemory(
				installedProject.dexJar,
				currentFormInitial,
				className
			) as? Map<String, Any?>

			Log.d("ACTION", "Groovy mainProcessing for action=$action → $result")
		}
	}


	fun onSubmit(blockId: UUID, values: Map<String, Any?>) {
		controller.submitForm(blockId, values)
	}


	private fun showHomeScreen() {
		val view = homeView
		val container = findViewById<FrameLayout>(binding.container.id)
		container.removeAllViews()
		container.addView(view)
	}


//	private fun showTasksScreen() {
//		val view = TextView(this).apply {
//			text = "Задачи"
//			textSize = 22f
//			setPadding(40, 40, 40, 40)
//		}
//		val container = findViewById<FrameLayout>(binding.container.id)
//		container.removeAllViews()
//		container.addView(view)
//	}


	private fun showSettingsScreen() {
		val container = findViewById<FrameLayout>(binding.container.id)
		container.removeAllViews()
		val fragment = SettingsFragment()
		supportFragmentManager.beginTransaction()
			.replace(container.id, fragment)
			.commit()
	}


	private fun runUpdateCheckOnce() {
		val once = OneTimeWorkRequestBuilder<UpdateCheckWorker>().build()
		WorkManager.getInstance(this)
			.enqueueUniqueWork(
				"script_update_check_once",
				ExistingWorkPolicy.REPLACE,
				once
			)
	}


	private fun startUpdateScheduler() {
		val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(15, TimeUnit.MINUTES).build()
		WorkManager
			.getInstance(this)
			.enqueueUniquePeriodicWork(
				"script_update_check",
				ExistingPeriodicWorkPolicy.UPDATE,
				request
			)
	}


	override fun onDestroy() {
		super.onDestroy()
		if (instance === this) {
			instance = null
		}
		scope.cancel()
	}

	companion object {
		@SuppressLint("StaticFieldLeak")
		@Volatile
		var instance: MainActivity? = null
	}

}