package us.charliek.flyway.converter

import jakarta.inject.Singleton
import us.charliek.flyway.exception.UnsupportedDatabaseException

/** Picks the right converter for an R2DBC URL. */
@Singleton
class ConverterRegistry(
    private val converters: List<R2dbcToJdbcConverter>,
) {
    fun findConverter(r2dbcUrl: String): R2dbcToJdbcConverter =
        converters.find { it.supports(r2dbcUrl) }
            ?: throw UnsupportedDatabaseException(extractDatabase(r2dbcUrl))

    private fun extractDatabase(r2dbcUrl: String): String {
        val match = Regex("r2dbc:([^:]+)://").find(r2dbcUrl)
        return match?.groups?.get(1)?.value ?: "unknown"
    }
}
