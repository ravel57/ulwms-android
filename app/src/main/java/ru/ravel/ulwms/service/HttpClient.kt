package ru.ravel.ulwms.service

import io.reactivex.rxjava3.core.Single
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import ru.ravel.ulwms.dto.ScanResponse


class HttpClient() {

	fun sendScan(host: String, text: String, userId: Long): Single<ScanResponse> {
		val body = text.toRequestBody("text/plain".toMediaType())
		val url = "https://$host/api/v1/scan"
		return RetrofitProvider.api.sendScan(url = url, userId = userId, body = body)
	}

}