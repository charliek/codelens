package codelens.source.resolver

import codelens.core.model.MavenCoordinates
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for downloading source JARs from Maven Central.
 */
class MavenCentralClient(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    private val logger = LoggerFactory.getLogger(MavenCentralClient::class.java)

    /**
     * Downloads the source JAR for the given coordinates.
     *
     * @param coords The Maven coordinates to download
     * @return Result containing the JAR bytes on success, or an error on failure
     */
    fun downloadSourceJar(coords: MavenCoordinates): Result<ByteArray> {
        val url = coords.sourceJarUrl()
        logger.info("Downloading source JAR from: {}", url)

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "CodeLens/1.0")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val bytes = response.body?.bytes()
                        when {
                            bytes == null || bytes.isEmpty() -> {
                                logger.warn("Empty response body for {}", coords.toGradleNotation())
                                Result.failure(SourceJarNotFoundException(coords, "Empty response body"))
                            }
                            !isValidZipFile(bytes) -> {
                                logger.warn("Downloaded content is not a valid JAR/ZIP for {}", coords.toGradleNotation())
                                Result.failure(SourceJarDownloadException(coords, "Invalid JAR file format"))
                            }
                            else -> {
                                logger.info("Downloaded {} bytes for {}", bytes.size, coords.toGradleNotation())
                                Result.success(bytes)
                            }
                        }
                    }
                    response.code == 404 -> {
                        logger.info("Source JAR not found: {}", coords.toGradleNotation())
                        Result.failure(SourceJarNotFoundException(coords, "Not found (404)"))
                    }
                    else -> {
                        val message = "HTTP ${response.code}: ${response.message}"
                        logger.warn("Failed to download {}: {}", coords.toGradleNotation(), message)
                        Result.failure(SourceJarDownloadException(coords, message))
                    }
                }
            }
        } catch (e: IOException) {
            logger.error("Network error downloading {}: {}", coords.toGradleNotation(), e.message)
            Result.failure(SourceJarDownloadException(coords, "Network error: ${e.message}", e))
        }
    }

    /**
     * Checks if a source JAR exists without downloading it.
     *
     * @param coords The Maven coordinates to check
     * @return true if the source JAR exists on Maven Central
     */
    fun sourceJarExists(coords: MavenCoordinates): Boolean {
        val url = coords.sourceJarUrl()
        logger.debug("Checking if source JAR exists: {}", url)

        val request = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", "CodeLens/1.0")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: IOException) {
            logger.debug("Error checking source JAR: {}", e.message)
            false
        }
    }

    /**
     * Validates that the byte array starts with the ZIP magic number (PK\x03\x04).
     * JAR files are ZIP files with specific structure.
     */
    private fun isValidZipFile(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // ZIP files start with "PK\x03\x04" magic number
        return bytes[0] == 0x50.toByte() &&  // 'P'
               bytes[1] == 0x4B.toByte() &&  // 'K'
               bytes[2] == 0x03.toByte() &&
               bytes[3] == 0x04.toByte()
    }

    companion object {
        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

/**
 * Exception thrown when a source JAR is not found on Maven Central.
 */
class SourceJarNotFoundException(
    val coordinates: MavenCoordinates,
    message: String
) : Exception("Source JAR not found for ${coordinates.toGradleNotation()}: $message")

/**
 * Exception thrown when downloading a source JAR fails.
 */
class SourceJarDownloadException(
    val coordinates: MavenCoordinates,
    message: String,
    cause: Throwable? = null
) : Exception("Failed to download source JAR for ${coordinates.toGradleNotation()}: $message", cause)
