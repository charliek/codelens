package us.charliek.flyway.configuration

import io.micronaut.context.annotation.ConfigurationProperties

/** Configuration for the Flyway R2DBC migrator, bound from `flyway-r2dbc.*`. */
@ConfigurationProperties("flyway-r2dbc")
class FlywayR2dbcConfigurationProperties {
    var enabled: Boolean = true
    var r2dbcUrl: String = "r2dbc:postgresql://localhost:5432/app"
    var username: String = "app"
    var password: String = "secret"
    var locations: List<String> = listOf("classpath:db/migration")
    var baselineOnMigrate: Boolean = true
    var connectionRetries: Int = 3
}
