package com.txl.mebot.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.models.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ZImageCoverGeneratorTest {
    private lateinit var db: MebotDatabase
    private lateinit var settings: ModelSettingsRepository
    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MebotDatabase::class.java).build()
        settings = ModelSettingsRepository(db, AesGcmCredentialCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") })
        settings.save(ModelSettings(), ApiKeyChange.Replace(KEY), 0)
        Unit
    }
    @After fun tearDown() { db.close() }

    @Test fun requestUsesConfiguredModelWithOneTextPrompt768SquareAndNoPromptExtension() = runBlocking {
        val generator = ZImageCoverGenerator(settings, ModelHttpTransport { endpoint, key, body, timeout ->
            assertEquals(ModelSettings().imageEndpoint, endpoint)
            assertEquals(KEY, key); assertEquals(60_000, timeout)
            val request = JSONObject(body)
            assertEquals(setOf("model", "input", "parameters"), request.keys().asSequence().toSet())
            assertEquals("z-image-turbo", request.getString("model"))
            assertEquals("768*768", request.getJSONObject("parameters").getString("size"))
            assertFalse(request.getJSONObject("parameters").getBoolean("prompt_extend"))
            val messages = request.getJSONObject("input").getJSONArray("messages")
            assertEquals(1, messages.length())
            assertEquals("user", messages.getJSONObject(0).getString("role"))
            val content = messages.getJSONObject(0).getJSONArray("content")
            assertEquals(1, content.length())
            assertEquals(setOf("text"), content.getJSONObject(0).keys().asSequence().toSet())
            assertEquals("封面主题：买蔬菜", content.getJSONObject(0).getString("text"))
            assertFalse(body.contains(KEY))
            ModelHttpResponse(200, RESPONSE)
        })
        val result = generator.generate("封面主题：买蔬菜")
        assertEquals(IMAGE_URL, result.url)
        assertEquals("z-image-turbo", result.model)
        assertFalse(result.toString().contains("signature"))
    }

    @Test fun providerErrorsAndMalformedOrUnusableImageUrlsAreSanitized() = runBlocking {
        val cases = listOf(
            ModelHttpResponse(401, "{\"message\":\"$KEY\"}") to ConnectionResultKind.INVALID_KEY,
            ModelHttpResponse(429, "{\"code\":\"insufficient_quota\"}") to ConnectionResultKind.BILLING,
            ModelHttpResponse(200, "{\"output\":{\"task_id\":\"pending\"}}") to ConnectionResultKind.BAD_RESPONSE,
            ModelHttpResponse(200, RESPONSE.replace("https://", "http://")) to ConnectionResultKind.BAD_RESPONSE,
            ModelHttpResponse(200, RESPONSE.replace("images.example.com", "name:pass@images.example.com")) to ConnectionResultKind.BAD_RESPONSE,
            ModelHttpResponse(200, RESPONSE.replace("\"stop\"", "\"content_filter\"")) to ConnectionResultKind.BAD_RESPONSE,
        )
        for ((response, expected) in cases) {
            val generator = ZImageCoverGenerator(settings, ModelHttpTransport { _, _, _, _ -> response })
            val error = runCatching { generator.generate("封面") }.exceptionOrNull()
            assertTrue(error is ModelImageException)
            assertEquals(expected, (error as ModelImageException).kind)
            assertFalse(error.toString().contains(KEY)); assertFalse(error.toString().contains("signature"))
        }
    }

    @Test fun missingCredentialsAndCancellationNeverProduceAnAutomaticSecondRequest() = runBlocking {
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val generator = ZImageCoverGenerator(settings, ModelHttpTransport { _, _, _, _ -> calls++; started.complete(Unit); awaitCancellation() })
        val work = async { generator.generate("封面") }
        started.await(); work.cancelAndJoin()
        assertEquals(1, calls)
        settings.removeApiKey(1)
        val error = runCatching { generator.generate("封面") }.exceptionOrNull()
        assertEquals(ConnectionResultKind.PARAMETERS, (error as ModelImageException).kind)
        assertEquals(1, calls)
    }

    @Test fun downloadUsesHttpsGetWithoutApiCredentialsOrRedirectsAndClosesTheConnection() = runBlocking {
        val connection = FakeImageConnection(200, "image-bytes".toByteArray())
        val bytes = HttpsCoverImageDownloader { connection }.download(IMAGE_URL)
        assertEquals("image-bytes", bytes.toString(Charsets.UTF_8))
        assertEquals("GET", connection.requestMethod)
        assertNull(connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    @Test fun insecureRedirectedAndOversizedDownloadsAreRejectedBeforeReadingTheBody() = runBlocking {
        var connections = 0
        val downloader = HttpsCoverImageDownloader { connections++; error("不应建立 HTTP 连接") }
        assertTrue(runCatching { downloader.download(IMAGE_URL.replace("https://", "http://")) }.isFailure)
        assertEquals(0, connections)
        for (connection in listOf(FakeImageConnection(302, byteArrayOf()), FakeImageConnection(200, byteArrayOf(), 13L * 1024 * 1024))) {
            assertTrue(runCatching { HttpsCoverImageDownloader { connection }.download(IMAGE_URL) }.isFailure)
            assertFalse(connection.inputRead)
            assertTrue(connection.disconnected)
        }
    }

    private class FakeImageConnection(private val status: Int, private val bytes: ByteArray, private val length: Long = bytes.size.toLong()) : HttpsURLConnection(URL(IMAGE_URL)) {
        var disconnected = false
        var inputRead = false
        override fun getInputStream(): InputStream { inputRead = true; return ByteArrayInputStream(bytes) }
        override fun getResponseCode() = status
        override fun getContentLengthLong() = length
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = "TLS_TEST"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    private companion object {
        const val KEY = "sk-fake-cover-test"
        const val IMAGE_URL = "https://images.example.com/generated.png?signature=temporary"
        const val RESPONSE = "{\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":[{\"image\":\"$IMAGE_URL\"},{\"text\":\"封面\"}]}}]}}"
    }
}
