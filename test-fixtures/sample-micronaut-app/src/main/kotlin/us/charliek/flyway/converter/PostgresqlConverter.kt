package us.charliek.flyway.converter

import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/** Converts PostgreSQL R2DBC URLs to JDBC. */
@Singleton
class PostgresqlConverter : R2dbcToJdbcConverter {
    companion object {
        private val logger = LoggerFactory.getLogger(PostgresqlConverter::class.java)
    }

    override fun supports(r2dbcUrl: String): Boolean = r2dbcUrl.startsWith("r2dbc:postgresql://")

    override fun convert(r2dbcUrl: String, username: String, password: String): JdbcConnectionInfo {
        val jdbcUrl = r2dbcUrl.replace("r2dbc:postgresql://", "jdbc:postgresql://")
        logger.debug("Converted R2DBC URL '{}' to JDBC URL '{}'", r2dbcUrl, jdbcUrl)
        return JdbcConnectionInfo(jdbcUrl, username, password)
    }
}
