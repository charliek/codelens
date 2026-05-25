package us.charliek.flyway.exception

/** Raised when a migration fails at startup. */
class FlywayR2dbcMigrationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
