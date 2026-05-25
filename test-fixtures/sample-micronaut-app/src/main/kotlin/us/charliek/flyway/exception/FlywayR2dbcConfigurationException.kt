package us.charliek.flyway.exception

/** Raised when the Flyway R2DBC configuration is invalid. */
class FlywayR2dbcConfigurationException(
    message: String,
) : RuntimeException(message)
