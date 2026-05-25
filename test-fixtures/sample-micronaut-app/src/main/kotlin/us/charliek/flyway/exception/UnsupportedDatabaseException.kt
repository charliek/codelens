package us.charliek.flyway.exception

/** Raised when no converter supports a given R2DBC URL. */
class UnsupportedDatabaseException(
    database: String,
) : RuntimeException("Unsupported database: $database")
