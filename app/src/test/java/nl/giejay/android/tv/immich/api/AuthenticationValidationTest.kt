package nl.giejay.android.tv.immich.api

import arrow.core.Either
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses actual Retrofit/OkHttp requests against a loopback HTTP endpoint, with public test keys. */
class AuthenticationValidationTest {
    private class Server(private val status: Int, private val body: String) : Closeable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val received = CompletableFuture<List<String>>()
        val host = "http://127.0.0.1:${socket.localPort}"

        init {
            executor.submit {
                try {
                    socket.accept().use { client ->
                        client.soTimeout = 5000
                        val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val headers = mutableListOf<String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headers.add(line)
                        }
                        received.complete(headers)
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val head = "HTTP/1.1 $status Test\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        client.getOutputStream().apply {
                            write(head.toByteArray(Charsets.US_ASCII))
                            write(bytes)
                            flush()
                        }
                    }
                } catch (error: Exception) {
                    received.completeExceptionally(error)
                }
            }
        }

        override fun close() {
            socket.close()
            executor.shutdownNow()
        }
    }

    private fun client(server: Server) = ApiClient(ApiClientConfig(server.host, " PUBLIC-test-key+/= ", false, false))

    @Test fun `authentication uses the entered key on the actual validation endpoint`(): Unit = runBlocking {
        Server(200, """{"authStatus":true}""").use { server ->
            assertTrue(client(server).validateAuthentication().isRight())
            val headers = server.received.get(5, TimeUnit.SECONDS)
            assertEquals("POST /api/auth/validateToken HTTP/1.1", headers.first())
            assertEquals(listOf("x-api-key: PUBLIC-test-key+/="), headers.filter { it.startsWith("x-api-key:", true) })
        }
    }

    @Test fun `401 is reported as failed authentication instead of a successful sign in`(): Unit = runBlocking {
        Server(401, """{"message":"Invalid API key","error":"Unauthorized","statusCode":401}""").use { server ->
            val result = client(server).validateAuthentication()
            assertTrue(result is Either.Left)
            val message = (result as Either.Left).value
            assertTrue(message.contains("401"))
            assertTrue(message.contains("Invalid API key"))
        }
    }

    @Test fun `200 without affirmative auth status is not accepted`(): Unit = runBlocking {
        Server(200, """{"authStatus":false}""").use { server ->
            assertTrue(client(server).validateAuthentication().isLeft())
        }
    }

    @Test fun `unrelated successful JSON is not accepted as authentication`(): Unit = runBlocking {
        Server(200, """{"message":"proxy response"}""").use { server ->
            assertTrue(client(server).validateAuthentication().isLeft())
        }
    }
}
