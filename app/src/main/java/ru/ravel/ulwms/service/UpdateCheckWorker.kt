package ru.ravel.ulwms.service

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import ru.ravel.ulwms.R
import ru.ravel.ulwms.utils.sha256

class UpdateCheckWorker(
	appContext: Context,
	workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

	@SuppressLint("CheckResult")
	override fun doWork(): Result {
		try {
			val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
			val host = prefs.getString("driver_server_url", applicationContext.getString(R.string.driver_api_url))!!
			val files = InstalledProject.getAllProjectFiles(applicationContext)
			val filteredFiles = files.filter { it.extension == "zip" }
			val response = RetrofitProvider.api
				.downloadFile("https://$host/api/v1/get-all-scripts-names")
				.blockingGet()
			val valueTypeRef = object : TypeReference<List<String>>() {}
			val allFileNames: List<String> = response.body()
				?.use { body ->
					val stream = body.byteStream()
					ObjectMapper().readValue(stream, valueTypeRef)
				}
				?: emptyList()
			filteredFiles
				.map { "https://$host/api/v1/get-script/${it.name}:${it.sha256()}" }
				.map { InstalledProject.install(applicationContext, it).blockingGet() }
			allFileNames
				.filter { it !in filteredFiles.map { file -> file.name } }
				.map { "https://$host/api/v1/get-script/$it" }
				.map { InstalledProject.install(applicationContext, it).blockingGet() }
			return Result.success()
		} catch (e: Exception) {
			Log.e("UpdateCheckWorker", e.localizedMessage, e)
			return Result.retry()
		}
	}
}
