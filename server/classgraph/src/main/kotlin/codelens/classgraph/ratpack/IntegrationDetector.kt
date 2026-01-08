package codelens.classgraph.ratpack

import codelens.classgraph.ClassGraphProvider
import codelens.core.model.ClassFilter
import codelens.core.model.ClassInfo
import codelens.core.model.ratpack.*

/**
 * Registry of known external integration types and their FQNs.
 */
object IntegrationTypes {
    // HTTP Clients
    val HTTP_CLIENTS = mapOf(
        "ratpack.http.client.HttpClient" to IntegrationSubType.RATPACK_HTTP_CLIENT,
        "org.asynchttpclient.AsyncHttpClient" to IntegrationSubType.ASYNC_HTTP_CLIENT,
        "org.apache.http.client.HttpClient" to IntegrationSubType.APACHE_HTTP_CLIENT,
        "org.apache.http.impl.client.CloseableHttpClient" to IntegrationSubType.APACHE_HTTP_CLIENT,
        "okhttp3.OkHttpClient" to IntegrationSubType.OK_HTTP,
        "java.net.http.HttpClient" to IntegrationSubType.JAVA_HTTP_CLIENT,
        "retrofit2.Retrofit" to IntegrationSubType.RETROFIT
    )

    // Databases
    val DATABASES = mapOf(
        "software.amazon.awssdk.services.dynamodb.DynamoDbClient" to IntegrationSubType.DYNAMODB,
        "software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient" to IntegrationSubType.DYNAMODB,
        "com.amazonaws.services.dynamodbv2.AmazonDynamoDB" to IntegrationSubType.DYNAMODB,
        "javax.sql.DataSource" to IntegrationSubType.JDBC,
        "java.sql.Connection" to IntegrationSubType.JDBC,
        "com.zaxxer.hikari.HikariDataSource" to IntegrationSubType.HIKARI,
        "org.jooq.DSLContext" to IntegrationSubType.JOOQ,
        "org.hibernate.SessionFactory" to IntegrationSubType.HIBERNATE,
        "org.hibernate.Session" to IntegrationSubType.HIBERNATE,
        "io.lettuce.core.RedisClient" to IntegrationSubType.REDIS_LETTUCE,
        "io.lettuce.core.api.StatefulRedisConnection" to IntegrationSubType.REDIS_LETTUCE,
        "io.lettuce.core.cluster.RedisClusterClient" to IntegrationSubType.REDIS_LETTUCE,
        "redis.clients.jedis.Jedis" to IntegrationSubType.REDIS_JEDIS,
        "redis.clients.jedis.JedisPool" to IntegrationSubType.REDIS_JEDIS,
        "com.mongodb.client.MongoClient" to IntegrationSubType.MONGO,
        "com.mongodb.reactivestreams.client.MongoClient" to IntegrationSubType.MONGO,
        "org.elasticsearch.client.RestClient" to IntegrationSubType.ELASTICSEARCH,
        "org.elasticsearch.client.RestHighLevelClient" to IntegrationSubType.ELASTICSEARCH,
        "co.elastic.clients.elasticsearch.ElasticsearchClient" to IntegrationSubType.ELASTICSEARCH
    )

    // Message Queues
    val MESSAGE_QUEUES = mapOf(
        "software.amazon.awssdk.services.sqs.SqsClient" to IntegrationSubType.SQS,
        "software.amazon.awssdk.services.sqs.SqsAsyncClient" to IntegrationSubType.SQS,
        "com.amazonaws.services.sqs.AmazonSQS" to IntegrationSubType.SQS,
        "software.amazon.awssdk.services.sns.SnsClient" to IntegrationSubType.SNS,
        "software.amazon.awssdk.services.sns.SnsAsyncClient" to IntegrationSubType.SNS,
        "com.amazonaws.services.sns.AmazonSNS" to IntegrationSubType.SNS,
        "org.apache.kafka.clients.producer.KafkaProducer" to IntegrationSubType.KAFKA,
        "org.apache.kafka.clients.consumer.KafkaConsumer" to IntegrationSubType.KAFKA,
        "com.rabbitmq.client.Connection" to IntegrationSubType.RABBITMQ,
        "com.rabbitmq.client.Channel" to IntegrationSubType.RABBITMQ,
        "software.amazon.awssdk.services.kinesis.KinesisClient" to IntegrationSubType.KINESIS,
        "software.amazon.awssdk.services.kinesis.KinesisAsyncClient" to IntegrationSubType.KINESIS
    )

    // Caches
    val CACHES = mapOf(
        "com.github.benmanes.caffeine.cache.Cache" to IntegrationSubType.CAFFEINE,
        "com.github.benmanes.caffeine.cache.LoadingCache" to IntegrationSubType.CAFFEINE,
        "com.github.benmanes.caffeine.cache.AsyncCache" to IntegrationSubType.CAFFEINE,
        "com.google.common.cache.Cache" to IntegrationSubType.GUAVA_CACHE,
        "com.google.common.cache.LoadingCache" to IntegrationSubType.GUAVA_CACHE,
        "org.ehcache.Cache" to IntegrationSubType.EHCACHE,
        "net.spy.memcached.MemcachedClient" to IntegrationSubType.MEMCACHED
    )

    // gRPC
    val GRPC = mapOf(
        "io.grpc.ManagedChannel" to IntegrationSubType.GRPC_CHANNEL,
        "io.grpc.Channel" to IntegrationSubType.GRPC_CHANNEL
    )

    // File Storage
    val FILE_STORAGE = mapOf(
        "software.amazon.awssdk.services.s3.S3Client" to IntegrationSubType.S3,
        "software.amazon.awssdk.services.s3.S3AsyncClient" to IntegrationSubType.S3,
        "com.amazonaws.services.s3.AmazonS3" to IntegrationSubType.S3,
        "com.google.cloud.storage.Storage" to IntegrationSubType.GCS
    )

    /**
     * Get integration type and subtype for a given FQN.
     */
    fun classifyType(typeFqn: String): Pair<IntegrationType, IntegrationSubType>? {
        // Check base type (strip generics)
        val baseType = typeFqn.substringBefore('<')

        HTTP_CLIENTS[baseType]?.let { return IntegrationType.HTTP_CLIENT to it }
        DATABASES[baseType]?.let { return IntegrationType.DATABASE to it }
        MESSAGE_QUEUES[baseType]?.let { return IntegrationType.MESSAGE_QUEUE to it }
        CACHES[baseType]?.let { return IntegrationType.CACHE to it }
        GRPC[baseType]?.let { return IntegrationType.GRPC to it }
        FILE_STORAGE[baseType]?.let { return IntegrationType.FILE_STORAGE to it }

        // Check for gRPC stubs (end with Grpc$...Stub)
        if (baseType.contains("Grpc\$") && baseType.contains("Stub")) {
            return IntegrationType.GRPC to IntegrationSubType.GRPC_STUB
        }

        return null
    }

    /**
     * Get all known FQNs for a given integration type.
     */
    fun getFqnsForType(type: IntegrationType): Set<String> {
        return when (type) {
            IntegrationType.HTTP_CLIENT -> HTTP_CLIENTS.keys
            IntegrationType.DATABASE -> DATABASES.keys
            IntegrationType.MESSAGE_QUEUE -> MESSAGE_QUEUES.keys
            IntegrationType.CACHE -> CACHES.keys
            IntegrationType.GRPC -> GRPC.keys
            IntegrationType.FILE_STORAGE -> FILE_STORAGE.keys
            IntegrationType.OTHER -> emptySet()
        }
    }
}

/**
 * Detects external service integrations in the codebase.
 */
class IntegrationDetector(
    private val classGraphProvider: ClassGraphProvider
) {
    /**
     * Analyze a specific class for integrations.
     */
    fun analyzeClass(fqn: String): ClassIntegrations? {
        val classInfo = classGraphProvider.getClass(fqn) ?: return null
        return analyzeClassInfo(classInfo)
    }

    /**
     * Analyze ClassInfo for integrations.
     */
    private fun analyzeClassInfo(classInfo: ClassInfo): ClassIntegrations {
        val integrations = mutableListOf<IntegrationUsage>()

        // Check fields
        for (field in classInfo.fields) {
            IntegrationTypes.classifyType(field.type)?.let { (type, subType) ->
                integrations.add(
                    IntegrationUsage(
                        location = IntegrationLocation.FIELD,
                        name = field.name,
                        typeFqn = field.type,
                        integrationType = type,
                        subType = subType
                    )
                )
            }
        }

        // Check methods
        for (method in classInfo.methods) {
            // Check constructor parameters (constructors have name <init>)
            if (method.name == "<init>") {
                for (param in method.parameters) {
                    IntegrationTypes.classifyType(param.type)?.let { (type, subType) ->
                        integrations.add(
                            IntegrationUsage(
                                location = IntegrationLocation.CONSTRUCTOR_PARAMETER,
                                name = param.name,
                                typeFqn = param.type,
                                integrationType = type,
                                subType = subType
                            )
                        )
                    }
                }
            } else {
                // Check method parameters
                for (param in method.parameters) {
                    IntegrationTypes.classifyType(param.type)?.let { (type, subType) ->
                        integrations.add(
                            IntegrationUsage(
                                location = IntegrationLocation.METHOD_PARAMETER,
                                name = param.name,
                                typeFqn = param.type,
                                context = method.name,
                                integrationType = type,
                                subType = subType
                            )
                        )
                    }
                }

                // Check return type
                IntegrationTypes.classifyType(method.returnType)?.let { (type, subType) ->
                    integrations.add(
                        IntegrationUsage(
                            location = IntegrationLocation.METHOD_RETURN_TYPE,
                            name = method.name,
                            typeFqn = method.returnType,
                            integrationType = type,
                            subType = subType
                        )
                    )
                }
            }
        }

        // Build type summary
        val typeSummary = integrations.groupBy { it.integrationType }
            .mapValues { it.value.size }

        return ClassIntegrations(
            classFqn = classInfo.name.fqn,
            simpleName = classInfo.name.simpleName,
            packageName = classInfo.name.packageName,
            source = classInfo.source,
            integrations = integrations,
            typeSummary = typeSummary
        )
    }

    /**
     * Get project-wide integration summary.
     */
    fun getProjectSummary(includeLibraries: Boolean = false): ProjectIntegrationSummary {
        val allIntegrations = mutableListOf<ClassIntegrations>()
        val filter = ClassFilter(includeLibraries = includeLibraries)
        val classes = classGraphProvider.listClasses(filter)

        for (classSummary in classes) {
            val classIntegrations = analyzeClass(classSummary.fqn)
            if (classIntegrations != null && classIntegrations.integrations.isNotEmpty()) {
                allIntegrations.add(classIntegrations)
            }
        }

        // Build type breakdown
        val typeBreakdown = mutableMapOf<IntegrationType, Int>()
        for (classInt in allIntegrations) {
            for (usage in classInt.integrations) {
                typeBreakdown[usage.integrationType] = (typeBreakdown[usage.integrationType] ?: 0) + 1
            }
        }

        // Build integration summaries grouped by type+subtype
        val integrationMap = mutableMapOf<Pair<IntegrationType, IntegrationSubType>, MutableList<Pair<String, String>>>()
        for (classInt in allIntegrations) {
            for (usage in classInt.integrations) {
                val key = usage.integrationType to usage.subType
                integrationMap.getOrPut(key) { mutableListOf() }
                    .add(usage.typeFqn to classInt.classFqn)
            }
        }

        val integrations = integrationMap.map { (key, usages) ->
            val (type, subType) = key
            val classFqns = usages.map { it.second }.distinct()
            IntegrationSummary(
                type = type,
                subType = subType,
                primaryTypeFqn = usages.first().first,
                usageCount = usages.size,
                classCount = classFqns.size,
                sampleClasses = classFqns.take(5)
            )
        }.sortedWith(compareBy({ it.type }, { it.subType }))

        return ProjectIntegrationSummary(
            classesWithIntegrations = allIntegrations.size,
            totalUsages = allIntegrations.sumOf { it.integrations.size },
            typeBreakdown = typeBreakdown,
            integrations = integrations
        )
    }

    /**
     * Find all classes using a specific integration type.
     */
    fun findByType(
        type: IntegrationType,
        subType: IntegrationSubType? = null,
        includeLibraries: Boolean = false
    ): List<ClassIntegrations> {
        val filter = ClassFilter(includeLibraries = includeLibraries)
        val classes = classGraphProvider.listClasses(filter)

        return classes.mapNotNull { classSummary ->
            val classIntegrations = analyzeClass(classSummary.fqn) ?: return@mapNotNull null
            val filtered = classIntegrations.integrations.filter { usage ->
                usage.integrationType == type && (subType == null || usage.subType == subType)
            }
            if (filtered.isEmpty()) null else classIntegrations.copy(
                integrations = filtered,
                typeSummary = filtered.groupBy { it.integrationType }.mapValues { it.value.size }
            )
        }
    }
}
