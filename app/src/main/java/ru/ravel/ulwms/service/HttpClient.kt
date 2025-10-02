package ru.ravel.ulwms.service

import android.content.ClipData
import io.reactivex.rxjava3.core.Single
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import ru.ravel.ulwms.dto.ScanResponse


class HttpClient() {

	fun sendScan(host: String, clipData: ClipData?): Single<ScanResponse> {
		val text = clipData?.getItemAt(0)?.text?.toString()
			?: return Single.error(IllegalArgumentException("Нет данных в буфере обмена"))
		val body = text.toRequestBody("text/plain".toMediaType())
		val url = "https://$host/api/v1/scan"
		return RetrofitProvider.api.sendScan(url = url, userId = 11, body = body)
	}

}