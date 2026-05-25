package us.charliek.flyway.configuration

import jakarta.inject.Singleton
import us.charliek.flyway.converter.ConverterRegistry
import us.charliek.flyway.converter.JdbcConnectionInfo

/** Resolves JDBC connection details from the configured R2DBC URL. */
@Singleton
class FlywayR2dbcConnectionResolver(
    private val config: FlywayR2dbcConfigurationProperties,
    private val converterRegistry: ConverterRegistry,
) {
    fun resolveJdbcConnection(): JdbcConnectionInfo {
        val converter = converterRegistry.findConverter(config.r2dbcUrl)
        return converter.convert(config.r2dbcUrl, config.username, config.password)
    }
}
