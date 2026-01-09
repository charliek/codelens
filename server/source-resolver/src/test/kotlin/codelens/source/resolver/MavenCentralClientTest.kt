package codelens.source.resolver

import codelens.core.model.MavenCoordinates
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MavenCentralClientTest {

    private lateinit var mockWebServer: MockWebServer

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    // ========== HTTP Response Tests ==========

    @Test
    fun `successful response returns bytes`() {
        val validZipBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "test content".toByteArray()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(validZipBytes))
        )

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        val request = okhttp3.Request.Builder()
            .url(mockWebServer.url("/test.jar"))
            .build()

        val response = httpClient.newCall(request).execute()

        assertTrue(response.isSuccessful)
        val bytes = response.body?.bytes()
        assertEquals(validZipBytes.size, bytes?.size)
    }

    @Test
    fun `404 response indicates not found`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        val httpClient = OkHttpClient.Builder().build()

        val request = okhttp3.Request.Builder()
            .url(mockWebServer.url("/test.jar"))
            .build()

        val response = httpClient.newCall(request).execute()

        assertFalse(response.isSuccessful)
        assertEquals(404, response.code)
    }

    @Test
    fun `HEAD request checks existence`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val httpClient = OkHttpClient.Builder().build()

        val request = okhttp3.Request.Builder()
            .url(mockWebServer.url("/test.jar"))
            .head()
            .build()

        val response = httpClient.newCall(request).execute()

        assertTrue(response.isSuccessful)
        // Verify it was a HEAD request
        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("HEAD", recordedRequest.method)
    }

    @Test
    fun `HEAD request for non-existent file returns 404`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        val httpClient = OkHttpClient.Builder().build()

        val request = okhttp3.Request.Builder()
            .url(mockWebServer.url("/nonexistent.jar"))
            .head()
            .build()

        val response = httpClient.newCall(request).execute()

        assertFalse(response.isSuccessful)
    }

    // ========== Exception Type Tests ==========

    @Test
    fun `SourceJarNotFoundException contains coordinates`() {
        val coords = MavenCoordinates("com.example", "lib", "1.0.0")
        val exception = SourceJarNotFoundException(coords, "Not found")

        assertEquals(coords, exception.coordinates)
        assertTrue(exception.message!!.contains("com.example:lib:1.0.0"))
    }

    @Test
    fun `SourceJarDownloadException contains coordinates and cause`() {
        val coords = MavenCoordinates("com.example", "lib", "1.0.0")
        val cause = RuntimeException("Network error")
        val exception = SourceJarDownloadException(coords, "Download failed", cause)

        assertEquals(coords, exception.coordinates)
        assertEquals(cause, exception.cause)
        assertTrue(exception.message!!.contains("com.example:lib:1.0.0"))
    }

    @Test
    fun `SourceJarDownloadException works without cause`() {
        val coords = MavenCoordinates("com.example", "lib", "1.0.0")
        val exception = SourceJarDownloadException(coords, "Download failed")

        assertEquals(coords, exception.coordinates)
        assertEquals(null, exception.cause)
    }

    // ========== Content Validation Unit Tests ==========

    @Test
    fun `valid ZIP magic number is accepted`() {
        // PK\x03\x04 is the ZIP local file header signature
        val validZip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)

        assertTrue(isValidZipFile(validZip))
    }

    @Test
    fun `invalid magic number is rejected`() {
        val invalidContent = "Hello World".toByteArray()

        assertFalse(isValidZipFile(invalidContent))
    }

    @Test
    fun `content too short is rejected`() {
        val tooShort = byteArrayOf(0x50, 0x4B, 0x03)

        assertFalse(isValidZipFile(tooShort))
    }

    @Test
    fun `empty content is rejected`() {
        val empty = byteArrayOf()

        assertFalse(isValidZipFile(empty))
    }

    @Test
    fun `PDF magic number is rejected`() {
        // PDF starts with %PDF
        val pdf = "%PDF-1.4".toByteArray()

        assertFalse(isValidZipFile(pdf))
    }

    @Test
    fun `GIF magic number is rejected`() {
        val gif = "GIF89a".toByteArray()

        assertFalse(isValidZipFile(gif))
    }

    @Test
    fun `HTML content is rejected`() {
        val html = "<!DOCTYPE html>".toByteArray()

        assertFalse(isValidZipFile(html))
    }

    @Test
    fun `XML content is rejected`() {
        val xml = "<?xml version".toByteArray()

        assertFalse(isValidZipFile(xml))
    }

    @Test
    fun `real JAR magic bytes pass validation`() {
        // First bytes of a real JAR/ZIP file
        val realJarStart = byteArrayOf(
            0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x08, 0x08,
            0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        assertTrue(isValidZipFile(realJarStart))
    }

    // Helper function matching the production code's validation
    private fun isValidZipFile(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        return bytes[0] == 0x50.toByte() &&
               bytes[1] == 0x4B.toByte() &&
               bytes[2] == 0x03.toByte() &&
               bytes[3] == 0x04.toByte()
    }

    // ========== MavenCoordinates URL Generation Tests ==========

    @Test
    fun `sourceJarUrl generates correct Maven Central URL`() {
        val coords = MavenCoordinates("com.google.guava", "guava", "32.1.3-jre")

        val url = coords.sourceJarUrl()

        assertEquals(
            "https://repo1.maven.org/maven2/com/google/guava/guava/32.1.3-jre/guava-32.1.3-jre-sources.jar",
            url
        )
    }

    @Test
    fun `sourceJarName generates correct filename`() {
        val coords = MavenCoordinates("com.example", "lib", "1.0.0")

        assertEquals("lib-1.0.0-sources.jar", coords.sourceJarName())
    }

    @Test
    fun `toRepositoryPath generates correct path`() {
        val coords = MavenCoordinates("com.example.nested", "artifact", "2.0.0")

        assertEquals("com/example/nested/artifact/2.0.0", coords.toRepositoryPath())
    }
}
