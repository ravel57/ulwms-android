package ru.ravel.ulwms.activity

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.ravel.lcpeandroid.AndroidOutputsRepository
import ru.ravel.lcpeandroid.AndroidProjectRepository
import ru.ravel.lcpeandroid.AndroidRunController
import ru.ravel.lcpeandroid.RunEvents
import ru.ravel.lcpecore.model.BlockType
import ru.ravel.lcpecore.model.CoreBlock
import ru.ravel.lcpecore.model.CoreProject
import ru.ravel.ulwms.R
import ru.ravel.ulwms.databinding.ActivityMainBinding
import ru.ravel.ulwms.dto.SharedViewModel
import ru.ravel.ulwms.lcpe.AndroidGroovyExecutor
import ru.ravel.ulwms.model.LayoutAction
import ru.ravel.ulwms.service.HttpClient
import ru.ravel.ulwms.service.InstalledProject
import ru.ravel.ulwms.service.ScriptLoader
import ru.ravel.ulwms.utils.JsonUiRenderer
import java.io.File


class MainActivity : AppCompatActivity() {

	private lateinit var appBarConfiguration: AppBarConfiguration
	private lateinit var binding: ActivityMainBinding
	val viewModel: SharedViewModel by viewModels()

	private val scope = MainScope()


	@SuppressLint("CheckResult")
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)

//		setSupportActionBar(binding.toolbar)
//		val navController = findNavController(R.id.nav_host_fragment_content_main)
//		appBarConfiguration = AppBarConfiguration(navController.graph)
//		setupActionBarWithNavController(navController, appBarConfiguration)
//		binding.fab.setOnClickListener {
//			onCameraClick()
//		}

		lowCodeLogic("https://dl.ravel57.ru/OuFerKAoQl")
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


//	urls:
//	test_http: https://dl.ravel57.ru/8hGhoRC8+8
//	test_quality_check_android: https://dl.ravel57.ru/OuFerKAoQl
	private fun lowCodeLogic(url: String) {
		lifecycleScope.launch {
			InstalledProject.install(this@MainActivity, url)
				.map { installed ->
					val repo = AndroidProjectRepository(this@MainActivity)
					val outputsRepo = AndroidOutputsRepository()
					val groovyExecutor = AndroidGroovyExecutor(
						this@MainActivity,
						ScriptLoader(this@MainActivity),
						installed.dexJar
					)
					val controller = AndroidRunController(
						context = this@MainActivity,
						projectRepo = repo,
						outputsRepo = outputsRepo,
						groovy = groovyExecutor,
						python = null,
						js = null
					)

					return@map Single.fromCallable {
						controller.runAsync(installed.project, installed.projectJson, object : RunEvents {
							override fun onStart(block: CoreBlock) {
							}

							override fun onOutput(block: CoreBlock, payload: Map<String, Any?>) {
								if (block.type == BlockType.FORM) {
									val code = File(block.codePath!!).readText()
									runOnUiThread {
										layoutByJson(code)
									}
								}
							}

							override fun onFinish(block: CoreBlock) {
							}

							override fun onError(block: CoreBlock, error: Throwable) {
								Log.e("FirstFragment", "❌ Ошибка в блоке ${block.name}: ${error.message}", error)
							}

							override fun onCompleted(project: CoreProject) {
								Log.i("FirstFragment", "Проект отработал все что было")
//								val holidays = project.blocks
//									.firstOrNull { it.type == BlockType.FORM }
//									?.outputsData
//									?.flatMap { (it["holidays"] as? List<*> ?: emptyList()) }
//									?.joinToString("\n")
//									?: "Нет данных"
//								runOnUiThread {
//									val json = assets.open("ui/holidays.json").bufferedReader().use { it.readText() }
//									val view = showJson(json)
//									val textView = view.findViewWithTag<TextView>("holidaysTitle")
//									textView.text = holidays
//								}
							}
						})
						installed.project
					}
				}
				.flatMap { it }
				.subscribeOn(Schedulers.io())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(
					{ project -> },
					{ err -> err.printStackTrace() }
				)
		}
	}


	@SuppressLint("CheckResult")
	private fun onCameraClick() {
		val host = getString(R.string.api_url)
		val clipboard = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip

		HttpClient().sendScan(host, clipboard)
			.observeOn(AndroidSchedulers.mainThread())
			.subscribe(
				{ response -> viewModel.instruction.value = response.message },
				{ e -> e.printStackTrace() }
			)
	}


	private fun layoutByJson(jsonStr: String): View {
		val actions: Map<String, () -> Unit> = mapOf(
			LayoutAction.OPEN_FORM.action to {
				val formJson = assets.open("ui/form.json").bufferedReader().use { it.readText() }
				layoutByJson(formJson)
			},
			LayoutAction.BACK_TO_MAIN.action to {
				val formJson = assets.open("ui/home.json").bufferedReader().use { it.readText() }
				layoutByJson(formJson)
			},
			LayoutAction.REFRESH_HOLIDAYS.action to {
				lowCodeLogic("https://dl.ravel57.ru/OuFerKAoQl")
			},
			LayoutAction.PRESSED_GOOD.action to {
				lowCodeLogic("https://dl.ravel57.ru/OuFerKAoQl")
			},
			LayoutAction.PRESSED_BAD.action to {
				lowCodeLogic("https://dl.ravel57.ru/OuFerKAoQl")
			},
		)
		val spec = JSONObject(jsonStr)

		@Suppress("UNCHECKED_CAST")
		val view = JsonUiRenderer.render(this, spec, actions)

		binding.root.removeAllViews()
		binding.root.addView(view)
		return view
	}


	override fun onDestroy() {
		super.onDestroy()
		scope.cancel()
	}

}