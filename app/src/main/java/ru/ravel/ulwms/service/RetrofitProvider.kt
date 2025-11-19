package ru.ravel.ulwms.service

import io.reactivex.rxjava3.schedulers.Schedulers
import okhttp3.CipherSuite
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.TlsVersion
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit
import retrofit2.converter.gson.GsonConverterFactory


object RetrofitProvider {

	@Volatile
	private var retrofit: Retrofit? = null

	private fun createClient(): OkHttpClient {
		val spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
			.tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
			.cipherSuites(
				CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
				CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
				CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
				CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
			)
			.build()

		return OkHttpClient.Builder()
			.connectionSpecs(listOf(spec, ConnectionSpec.CLEARTEXT))
			.protocols(listOf(Protocol.HTTP_1_1))
			.connectTimeout(60, TimeUnit.SECONDS)
			.readTimeout(120, TimeUnit.SECONDS)
			.writeTimeout(120, TimeUnit.SECONDS)
			.retryOnConnectionFailure(true)
			.connectionPool(ConnectionPool(0, 5, TimeUnit.MINUTES))
			.build()
	}

	private fun createRetrofit(): Retrofit {
		return Retrofit.Builder()
			.baseUrl("https://ulwms.ravel57.ru")
			.client(createClient())
			.addConverterFactory(GsonConverterFactory.create())
			.addConverterFactory(ScalarsConverterFactory.create())
			.addCallAdapterFactory(RxJava3CallAdapterFactory.createWithScheduler(Schedulers.io()))
			.build()
	}

	val api: ApiService
		get() = (retrofit ?: createRetrofit().also { retrofit = it })
			.create(ApiService::class.java)

	/** Сброс клиента при SSL ошибках */
	fun resetClient() {
		retrofit = createRetrofit()
	}
}
