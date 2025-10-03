package ru.ravel.ulwms.service

import io.reactivex.rxjava3.core.Single
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url
import ru.ravel.ulwms.dto.ScanResponse

interface ApiService {

	@GET
	fun downloadFile(
		@Url url: String,
		@Header("Accept-Encoding") encoding: String = "identity",
		@Header("Connection") connection: String = "close"
	): Single<Response<ResponseBody>>

	@POST
	fun sendScan(
		@Url url: String,
		@Query("userId") userId: Long,
		@Body body: RequestBody
	): Single<ScanResponse>
}
