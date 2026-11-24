package com.txl.mebot.data

import android.app.Application
import com.txl.mebot.data.models.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ModelConnectionCheckerTest {
    private val access = ModelAccess(ModelSettings(), "sk-fake-test-key")

    @Test fun languageProbeUsesOnlyFixedTextAndDisablesThinkingAndStreaming() = runBlocking {
        var count = 0
        val checker = ModelConnectionChecker(ModelHttpTransport { endpoint, key, body, timeout ->
            count++
            assertEquals(access.settings.languageEndpoint, endpoint)
            assertEquals("sk-fake-test-key", key)
            assertEquals(30_000, timeout)
            val json = JSONObject(body)
            assertEquals("qwen3.8-flash", json.getString("model"))
            assertFalse(json.getBoolean("enable_thinking"))
            assertFalse(json.getBoolean("stream"))
            assertEquals(16, json.getInt("max_tokens"))
            assertEquals(1, json.getJSONArray("messages").length())
            ModelHttpResponse(200, LANGUAGE_OK)
        })
        assertEquals(ConnectionResultKind.SUCCESS, checker.check(access, ModelRole.LANGUAGE).kind)
        assertEquals(1, count)
    }

    @Test fun imageProbeUses768SquareWithoutPromptExtensionAndAcceptsOnlyImageResult() = runBlocking {
        val checker = ModelConnectionChecker(ModelHttpTransport { endpoint, _, body, timeout ->
            assertEquals(access.settings.imageEndpoint, endpoint)
            assertEquals(60_000, timeout)
            val json = JSONObject(body)
            assertEquals("z-image-turbo", json.getString("model"))
            assertEquals("768*768", json.getJSONObject("parameters").getString("size"))
            assertFalse(json.getJSONObject("parameters").getBoolean("prompt_extend"))
            val messages = json.getJSONObject("input").getJSONArray("messages")
            assertEquals(1, messages.length())
            assertEquals(1, messages.getJSONObject(0).getJSONArray("content").length())
            ModelHttpResponse(200, IMAGE_OK)
        })
        assertEquals(ConnectionResultKind.SUCCESS, checker.check(access, ModelRole.IMAGE).kind)
    }

    @Test fun httpAndProviderFailuresAreClassifiedWithoutEchoingSecrets() = runBlocking {
        val cases = listOf(
            Triple(401, "{\"error\":{\"code\":\"invalid_api_key\"}}", ConnectionResultKind.INVALID_KEY),
            Triple(403, "{\"code\":\"AccessDenied\"}", ConnectionResultKind.NO_PERMISSION),
            Triple(403, "{\"code\":\"AccessDenied.Unpurchased\"}", ConnectionResultKind.SERVICE_NOT_ENABLED),
            Triple(429, "{\"error\":{\"code\":\"insufficient_quota\"}}", ConnectionResultKind.BILLING),
            Triple(429, "{}", ConnectionResultKind.RATE_LIMIT),
            Triple(404, "{\"error\":{\"code\":\"ModelNotFound\"}}", ConnectionResultKind.MODEL_UNAVAILABLE),
            Triple(404, "<html>not found</html>", ConnectionResultKind.ENDPOINT),
            Triple(302, "", ConnectionResultKind.ENDPOINT),
            Triple(400, "{\"code\":\"InvalidParameter\",\"message\":\"model not exist\"}", ConnectionResultKind.MODEL_UNAVAILABLE),
            Triple(400, "{\"code\":\"InvalidParameter\"}", ConnectionResultKind.PARAMETERS),
            Triple(503, "{}", ConnectionResultKind.SERVICE_ERROR),
            Triple(200, "{\"code\":\"InvalidApiKey\",\"message\":\"sk-fake-test-key\"}", ConnectionResultKind.INVALID_KEY),
        )
        for ((status, body, expected) in cases) {
            val checker = ModelConnectionChecker(ModelHttpTransport { _, _, _, _ -> ModelHttpResponse(status, body) })
            val outcome = checker.check(access, ModelRole.LANGUAGE)
            assertEquals(expected, outcome.kind)
            assertEquals(status, outcome.httpStatus)
            assertFalse(outcome.toString().contains(access.apiKey))
        }
    }

    @Test fun htmlEmptyOrAsynchronousResponseDoesNotCountAsModelSuccess() = runBlocking {
        for (body in listOf("<html>OK</html>", "{}", "{\"choices\":[]}", "{\"choices\":[{\"message\":{\"content\":\"\"}}]}")) {
            val checker = ModelConnectionChecker(ModelHttpTransport { _, _, _, _ -> ModelHttpResponse(200, body) })
            assertEquals(ConnectionResultKind.BAD_RESPONSE, checker.check(access, ModelRole.LANGUAGE).kind)
        }
        for (body in listOf("{\"output\":{\"task_id\":\"pending\"}}", IMAGE_OK.replace("https://", "http://"))) {
            val checker = ModelConnectionChecker(ModelHttpTransport { _, _, _, _ -> ModelHttpResponse(200, body) })
            assertEquals(ConnectionResultKind.BAD_RESPONSE, checker.check(access, ModelRole.IMAGE).kind)
        }
    }

    @Test fun dnsTimeoutCertificateAndIoProblemsRemainDifferentFromKeyFailures() = runBlocking {
        val cases = listOf(
            UnknownHostException() to ConnectionResultKind.DNS,
            SocketTimeoutException() to ConnectionResultKind.TIMEOUT,
            SSLHandshakeException("invalid certificate") to ConnectionResultKind.TLS,
            IOException("network disconnected") to ConnectionResultKind.NETWORK,
        )
        for ((error, expected) in cases) {
            val checker = ModelConnectionChecker(ModelHttpTransport { _, _, _, _ -> throw error })
            assertEquals(expected, checker.check(access, ModelRole.LANGUAGE).kind)
        }
    }

    @Test fun cancellationIsPropagatedAndNeverRetried() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var count = 0
        val checker = ModelConnectionChecker(ModelHttpTransport { _, _, _, _ -> count++; started.complete(Unit); awaitCancellation() })
        val checking = async { checker.check(access, ModelRole.IMAGE) }
        started.await()
        checking.cancelAndJoin()
        assertTrue(checking.isCancelled)
        assertEquals(1, count)
    }

    @Test fun httpsTransportSetsBearerHeaderAndDisablesRedirects() = runBlocking {
        val connection = FakeConnection(200, LANGUAGE_OK)
        val transport = HttpsModelTransport { connection }
        val response = transport.post("https://api.example.com/check", access.apiKey, "{}", 1234)
        assertEquals(200, response.status)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("Bearer ${access.apiKey}", connection.getRequestProperty("Authorization"))
        assertEquals("application/json; charset=utf-8", connection.getRequestProperty("Content-Type"))
        assertEquals("POST", connection.requestMethod)
        assertEquals(1234, connection.readTimeout)
        assertEquals("{}", connection.written.toString(Charsets.UTF_8.name()))
        assertTrue(connection.disconnected)
    }

    @Test fun httpsTransportRejectsLargeResponsesAndDoesNotReadRedirectBody() = runBlocking {
        val huge = FakeConnection(200, "x".repeat(128 * 1024 + 1))
        assertTrue(runCatching { HttpsModelTransport { huge }.post("https://api.example.com/check", access.apiKey, "{}", 1000) }.isFailure)
        assertTrue(huge.disconnected)
        val redirect = FakeConnection(302, "should not read")
        assertEquals("", HttpsModelTransport { redirect }.post("https://api.example.com/check", access.apiKey, "{}", 1000).body)
        assertFalse(redirect.inputRead)
    }

    @Test fun cancellingHttpsReadDisconnectsTheUnderlyingConnection() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val connection = object : FakeConnection(200, "") {
            override fun getInputStream(): InputStream {
                started.complete(Unit)
                released.await(5, TimeUnit.SECONDS)
                throw IOException("connection closed")
            }
            override fun disconnect() { super.disconnect(); released.countDown() }
        }
        val work = async { HttpsModelTransport { connection }.post("https://api.example.com/check", access.apiKey, "{}", 1000) }
        started.await()
        work.cancelAndJoin()
        assertTrue(released.await(1, TimeUnit.SECONDS))
        assertTrue(connection.disconnected)
        assertTrue(work.isCancelled)
    }

    private open class FakeConnection(private val status: Int, private val body: String) : HttpsURLConnection(URL("https://api.example.com/check")) {
        val written = ByteArrayOutputStream()
        @Volatile var disconnected = false
        var inputRead = false
        override fun getOutputStream() = written
        override fun getInputStream(): InputStream { inputRead = true; return ByteArrayInputStream(body.toByteArray()) }
        override fun getErrorStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun getResponseCode() = status
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = "TLS_TEST"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    companion object {
        private const val LANGUAGE_OK = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}"
        private const val IMAGE_OK = "{\"output\":{\"choices\":[{\"message\":{\"content\":[{\"image\":\"https://images.example.com/test.png?token=temporary\"}]}}]}}"
    }
}
