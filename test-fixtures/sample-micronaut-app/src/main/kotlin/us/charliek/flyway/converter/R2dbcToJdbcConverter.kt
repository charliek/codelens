package us.charliek.flyway.converter

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

/** Converts an R2DBC URL into JDBC connection details Flyway can use. */
interface R2dbcToJdbcConverter {
    fun supports(r2dbcUrl: String): Boolean

    fun convert(r2dbcUrl: String, username: String, password: String): JdbcConnectionInfo
}

/** JDBC connection details, with a Hikari-backed DataSource builder. */
data class JdbcConnectionInfo(
    val url: String,
    val username: String,
    val password: String,
) {
    fun toDataSource(): DataSource {
        val config =
            HikariConfig().apply {
                jdbcUrl = url
                this.username = this@JdbcConnectionInfo.username
                this.password = this@JdbcConnectionInfo.password
                maximumPoolSize = 2
                minimumIdle = 0
                connectionTimeout = 30000
            }
        return HikariDataSource(config)
    }
}
