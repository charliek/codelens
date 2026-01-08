package codelens.core.model.ratpack

import codelens.core.model.ClassSource
import kotlinx.serialization.Serializable

// ============================================================================
// External Service Integration Models
// ============================================================================

/**
 * Primary type of external service integration.
 */
@Serializable
enum class IntegrationType {
    /** HTTP client for outbound requests */
    HTTP_CLIENT,
    /** Database access */
    DATABASE,
    /** Message queue (SQS, Kafka, RabbitMQ, etc.) */
    MESSAGE_QUEUE,
    /** Cache (Redis, Memcached, etc.) */
    CACHE,
    /** gRPC client */
    GRPC,
    /** File/object storage (S3, etc.) */
    FILE_STORAGE,
    /** Other external service */
    OTHER
}

/**
 * Specific subtype/implementation of integration.
 */
@Serializable
enum class IntegrationSubType {
    // HTTP Clients
    RATPACK_HTTP_CLIENT,
    ASYNC_HTTP_CLIENT,
    APACHE_HTTP_CLIENT,
    OK_HTTP,
    JAVA_HTTP_CLIENT,
    RETROFIT,

    // Databases
    DYNAMODB,
    JDBC,
    HIKARI,
    JOOQ,
    HIBERNATE,
    REDIS_LETTUCE,
    REDIS_JEDIS,
    MONGO,
    ELASTICSEARCH,

    // Message Queues
    SQS,
    SNS,
    KAFKA,
    RABBITMQ,
    KINESIS,

    // Caches
    CAFFEINE,
    GUAVA_CACHE,
    EHCACHE,
    MEMCACHED,

    // gRPC
    GRPC_STUB,
    GRPC_CHANNEL,

    // Storage
    S3,
    GCS,

    // Other
    UNKNOWN
}

/**
 * Where the integration is used in the class.
 */
@Serializable
enum class IntegrationLocation {
    /** Field in the class */
    FIELD,
    /** Constructor parameter */
    CONSTRUCTOR_PARAMETER,
    /** Method parameter */
    METHOD_PARAMETER,
    /** Method return type */
    METHOD_RETURN_TYPE
}

/**
 * A single usage of an external integration.
 */
@Serializable
data class IntegrationUsage(
    /** Location in the class */
    val location: IntegrationLocation,
    /** Name (field name, parameter name, etc.) */
    val name: String,
    /** FQN of the integration type */
    val typeFqn: String,
    /** Additional context (e.g., method name for parameters) */
    val context: String? = null,
    /** Detected integration type */
    val integrationType: IntegrationType,
    /** Detected subtype */
    val subType: IntegrationSubType
)

/**
 * All integrations detected in a single class.
 */
@Serializable
data class ClassIntegrations(
    /** Class FQN */
    val classFqn: String,
    /** Simple class name */
    val simpleName: String,
    /** Package name */
    val packageName: String,
    /** Class source */
    val source: ClassSource,
    /** All detected integrations */
    val integrations: List<IntegrationUsage>,
    /** Summary by type */
    val typeSummary: Map<IntegrationType, Int>
)

/**
 * Summary of integration usage across the project.
 */
@Serializable
data class IntegrationSummary(
    /** Type of integration */
    val type: IntegrationType,
    /** Subtype of integration */
    val subType: IntegrationSubType,
    /** Primary type FQN for this integration */
    val primaryTypeFqn: String,
    /** Number of usages */
    val usageCount: Int,
    /** Number of classes using this integration */
    val classCount: Int,
    /** Sample classes using this integration (up to 5) */
    val sampleClasses: List<String>
)

/**
 * Project-wide integration summary.
 */
@Serializable
data class ProjectIntegrationSummary(
    /** Total classes with integrations */
    val classesWithIntegrations: Int,
    /** Total integration usages */
    val totalUsages: Int,
    /** Breakdown by type */
    val typeBreakdown: Map<IntegrationType, Int>,
    /** All detected integrations grouped by type */
    val integrations: List<IntegrationSummary>
)

// ============================================================================
// Response Models
// ============================================================================

/**
 * Response for listing all integrations.
 */
@Serializable
data class IntegrationsListResponse(
    /** Summary of integrations */
    val summary: ProjectIntegrationSummary,
    /** Filter applied (if any) */
    val filter: IntegrationFilterApplied? = null
)

/**
 * Applied filter summary.
 */
@Serializable
data class IntegrationFilterApplied(
    /** Type filter */
    val type: IntegrationType? = null,
    /** SubType filter */
    val subType: IntegrationSubType? = null
)

/**
 * Response for class integrations.
 */
@Serializable
data class ClassIntegrationsResponse(
    /** Class integrations */
    val classIntegrations: ClassIntegrations
)

/**
 * Response for finding integrations by type.
 */
@Serializable
data class IntegrationUsagesResponse(
    /** Integration type queried */
    val type: IntegrationType,
    /** SubType queried (if any) */
    val subType: IntegrationSubType? = null,
    /** Classes using this integration */
    val classes: List<ClassIntegrations>,
    /** Total count */
    val totalCount: Int
)
