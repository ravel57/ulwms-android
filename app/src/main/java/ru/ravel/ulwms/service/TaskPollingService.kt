package ru.ravel.ulwms.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import ru.ravel.ulwms.R
import ru.ravel.ulwms.activity.MainActivity


class TaskPollingService : Service() {

	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	override fun onCreate() {
		super.onCreate()
		startForegroundService()
		startPolling()
	}

	@SuppressLint("ForegroundServiceType")
	private fun startForegroundService() {
		val channelId = "task_polling"
		if (Build.VERSION.SDK_INT >= 26) {
			val channel = NotificationChannel(
				channelId,
				"Task polling",
				NotificationManager.IMPORTANCE_LOW
			)
			val nm = getSystemService(NotificationManager::class.java)
			nm.createNotificationChannel(channel)
		}
		val notification: Notification =
			Notification.Builder(this, channelId)
				.setContentTitle("Поиск новых задач…")
				.setSmallIcon(R.drawable.ic_launcher_foreground)
				.build()

		startForeground(1, notification)
	}

	private fun startPolling() {
		scope.launch {
			while (isActive) {
				try {
					checkForTasks()
				} catch (e: Exception) {
					Log.e("TaskPollingService", "Ошибка проверки задач", e)
				}

				delay(30_000)
			}
		}
	}

	private fun checkForTasks() {
		val prefs = PreferenceManager.getDefaultSharedPreferences(this)
		val host = prefs.getString("driver_server_url", getString(R.string.driver_api_url))!!
		val userId = prefs.getString("user_id", "0")!!.toLong()
		// что сервер присылал в прошлый раз
		val lastScenario = prefs.getString("last_scenario_name", "") ?: ""
		// 1. Запрос к серверу: есть ли сейчас задачи / какой сценарий нужен
		val resp = RetrofitProvider.api
			.hasNewTasks("https://$host/api/v1/has-new-tasks/$userId")
			.blockingGet()
		val scenarioNameRaw = resp.string().trim()
		// null, "null", пусто — это НЕТ задач
		if (scenarioNameRaw.isBlank() || scenarioNameRaw.equals("null", ignoreCase = true)) {
			Log.i("TaskPollingService", "Сервер вернул null → задач нет")
			InstalledProject.clearActiveProject(applicationContext)
			val last = prefs.getString("last_scenario_name", "")
			if (!last.isNullOrBlank()) {
				prefs.edit().putString("last_scenario_name", "").apply()
				MainActivity.instance?.runOnUiThread {
					MainActivity.instance?.reloadScenario()
				}
			}
			return
		}
		// 3. Есть сценарий
		val scenarioName = scenarioNameRaw.removeSuffix(".zip")
		Log.i("TaskPollingService", "Нужен сценарий: $scenarioName")
		// Если last пустой — значит впервые получаем задачу → запускаем
		val activeName = prefs.getString("active_project_name", "") ?: ""
		// НЕ перезапускаем сценарий только если он совпадает и уже активен
		if (scenarioName == lastScenario && scenarioName == activeName) {
			Log.i("TaskPollingService", "Сценарий тот же и уже активен → не перезапускаем")
			return
		}
		// 4. Новый/другой сценарий → скачиваем, устанавливаем, запоминаем, перезапускаем
		InstalledProject.setActiveProject(applicationContext, scenarioName)
		prefs.edit().putString("last_scenario_name", scenarioName).apply()
		MainActivity.instance?.runOnUiThread {
			MainActivity.instance?.reloadScenario()
		}
	}


	override fun onDestroy() {
		super.onDestroy()
		scope.cancel()
	}

	override fun onBind(intent: Intent?): IBinder? = null
}
