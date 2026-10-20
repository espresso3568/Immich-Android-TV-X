package nl.giejay.android.tv.immich.api

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ApiClientFactoryTest {
    private val request = Request.Builder().url("https://immich.example.invalid/api/albums").build()

    private fun response(code: Int = 200): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test")
        .body("".toResponseBody()).build()

    private fun chain(incoming: Request = request, code: Int = 200): Interceptor.Chain {
        val chain = mock<Interceptor.Chain>()
        whenever(chain.request()).thenReturn(incoming)
        whenever(chain.proceed(any())).thenReturn(response(code))
        return chain
    }

    private fun interceptor(key: String) = ApiClientFactory.getClient(false, key, false).interceptors.first()

    @Test
    fun `requests carry the complete trimmed key exactly once`() {
        val chain = chain()
        interceptor("  PUBLIC-test-key+/=  ").intercept(chain)
        val sent = argumentCaptor<Request>()
        verify(chain).proceed(sent.capture())
        assertEquals(listOf("PUBLIC-test-key+/="), sent.firstValue.headers.values("x-api-key"))
    }

    @Test
    fun `existing header is replaced instead of sending two different keys`() {
        val chain = chain(request.newBuilder().header("x-api-key", "PUBLIC-old-key").build())
        interceptor("PUBLIC-current-key").intercept(chain)
        val sent = argumentCaptor<Request>()
        verify(chain).proceed(sent.capture())
        assertEquals(listOf("PUBLIC-current-key"), sent.firstValue.headers.values("x-api-key"))
    }

    @Test
    fun `network failure propagates without retrying an unauthenticated request`() {
        val chain = chain()
        val failure = IOException("Test connection failure")
        whenever(chain.proceed(any())).thenThrow(failure)
        val thrown = assertThrows(IOException::class.java) { interceptor("PUBLIC-key").intercept(chain) }
        assertSame(failure, thrown)
        verify(chain, times(1)).proceed(any())
    }

    @Test
    fun `invalid header characters fail locally without sending or exposing the key`() {
        val chain = chain()
        val key = "PUBLIC-key\ninvalid"
        val thrown = assertThrows(IOException::class.java) { interceptor(key).intercept(chain) }
        assertFalse(thrown.message.orEmpty().contains("PUBLIC-key"))
        verify(chain, never()).proceed(any())
    }

    @Test
    fun `blank key fails locally without an unauthenticated request`() {
        val chain = chain()
        assertThrows(IOException::class.java) { interceptor("   ").intercept(chain) }
        verify(chain, never()).proceed(any())
    }

    @Test
    fun `server 401 is returned once without retrying or disguising it as a header error`() {
        val chain = chain(code = 401)
        assertEquals(401, interceptor("PUBLIC-key").intercept(chain).code)
        verify(chain, times(1)).proceed(any())
    }

    @Test
    fun `retained image client resolves the current key for every request`() {
        var currentKey = "PUBLIC-first-key"
        val interceptor = ApiClientFactory.getClient(false, "PUBLIC-stale-key", false) {
            assertEquals(request.url, it.url)
            currentKey
        }.interceptors.first()
        val first = chain()
        interceptor.intercept(first)
        currentKey = "PUBLIC-replacement-key+/="
        val second = chain()
        interceptor.intercept(second)
        val sent = argumentCaptor<Request>()
        verify(first).proceed(sent.capture())
        verify(second).proceed(sent.capture())
        assertEquals(listOf("PUBLIC-first-key", "PUBLIC-replacement-key+/="), sent.allValues.map { it.header("x-api-key") })
    }

    @Test
    fun `key provider can reject old server requests before a key is sent`() {
        val chain = chain()
        val interceptor = ApiClientFactory.getClient(false, "PUBLIC-stale-key", false) {
            throw IOException("Request belongs to an old server")
        }.interceptors.first()
        assertThrows(IOException::class.java) { interceptor.intercept(chain) }
        verify(chain, never()).proceed(any())
    }
}
