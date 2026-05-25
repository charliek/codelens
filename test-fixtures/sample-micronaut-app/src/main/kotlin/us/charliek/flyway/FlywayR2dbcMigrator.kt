package us.charliek.flyway

import com.zaxxer.hikari.HikariDataSource
import io.micronaut.context.annotation.Requires
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.context.event.StartupEvent
import jakarta.inject.Singleton
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import us.charliek.flyway.configuration.FlywayR2dbcConfigurationProperties
import us.charliek.flyway.configuration.FlywayR2dbcConnectionResolver
import us.charliek.flyway.exception.FlywayR2dbcMigrationException
import java.sql.SQLException
import javax.sql.DataSource

/** Runs Flyway migrations at application startup, over a JDBC view of R2DBC config. */
@Singleton
@Requires(property = "flyway-r2dbc.enabled", value = "true")
class FlywayR2dbcMigrator(
    private val config: FlywayR2dbcConfigurationProperties,
    private val connectionResolver: FlywayR2dbcConnectionResolver,
) : ApplicationEventListener<StartupEvent> {
    companion object {
        private val logger = LoggerFactory.getLogger(FlywayR2dbcMigrator::class.java)
    }

    override fun onApplicationEvent(event: StartupEvent) {
        logger.info("Starting Flyway R2DBC migrations...")
        val startTime = System.currentTimeMillis()
        var dataSource: DataSource? = null
        try {
            dataSource = connectionResolver.resolveJdbcConnection().toDataSource()
            validateConnection(dataSource)
            val flyway =
                Flyway.configure()
                    .dataSource(dataSource)
                    .locations(*config.locations.toTypedArray())
                    .baselineOnMigrate(config.baselineOnMigrate)
                    .load()
            flyway.migrate()
            val durationMs = System.currentTimeMillis() - startTime
            logger.info("Flyway R2DBC migrations completed in {}ms", durationMs)
        } catch (e: SQLException) {
            logger.error("Flyway R2DBC migration failed", e)
            throw FlywayR2dbcMigrationException("Database migration failed during application startup", e)
        } finally {
            (dataSource as? HikariDataSource)?.close()
        }
    }

    private fun validateConnection(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            if (!connection.isValid(30)) {
                throw SQLException("Database connection validation failed")
            }
        }
    }
}
