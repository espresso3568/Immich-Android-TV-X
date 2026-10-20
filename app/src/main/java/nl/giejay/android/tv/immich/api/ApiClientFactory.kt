package nl.giejay.android.tv.immich.api

import nl.giejay.android.tv.immich.api.interceptor.ResponseLoggingInterceptor
import nl.giejay.android.tv.immich.api.util.UnsafeOkHttpClient
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

object ApiClientFactory {

    fun getClient(
        disableSsl: Boolean,
        apiKey: String,
        debugMode: Boolean,
        apiKeyProvider: (Request) -> String = { apiKey }
    ): OkHttpClient {
        val apiKeyInterceptor = interceptor(apiKeyProvider)
        val builder = if (disableSsl)
            UnsafeOkHttpClient.unsafeOkHttpClient()
        else OkHttpClient.Builder()
        builder.addInterceptor(apiKeyInterceptor)
        return if(debugMode){
            builder.addInterceptor(ResponseLoggingInterceptor()).build()
        } else
            builder.build()
    }

    private fun interceptor(apiKeyProvider: (Request) -> String): Interceptor = Interceptor { chain ->
        val key = apiKeyProvider(chain.request()).trim()
        if (key.isEmpty()) {
            throw IOException("API key is empty. Open Server settings and sign in again.")
        }
        val newRequest = try {
            chain.request().newBuilder()
                .header("x-api-key", key)
                .build()
        } catch (_: IllegalArgumentException) {
            // OkHttp's exception can include the header value; never retain it as a cause.
            throw IOException("API key contains invalid header characters. Re-enter it in Server settings.")
        }
        // Network failures must propagate. Retrying chain.request() would silently drop the key.
        chain.proceed(newRequest)
    }
}
