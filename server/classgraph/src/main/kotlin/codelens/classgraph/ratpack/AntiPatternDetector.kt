package codelens.classgraph.ratpack

import codelens.classgraph.ClassGraphProvider
import codelens.core.model.ClassFilter
import codelens.core.model.ratpack.*
import org.slf4j.LoggerFactory

/**
 * Types and patterns that indicate potential anti-patterns in Ratpack handlers.
 */
object AntiPatternIndicators {
    // JDBC types that indicate database calls
    val JDBC_TYPES =
        setOf(
            "java.sql.Connection",
            "java.sql.Statement",
            "java.sql.PreparedStatement",
            "java.sql.CallableStatement",
            "java.sql.ResultSet",
            "javax.sql.DataSource",
        )

    // Blocking HTTP client types
    val BLOCKING_HTTP_TYPES =
        setOf(
            "org.apache.http.client.HttpClient",
            "org.apache.http.impl.client.CloseableHttpClient",
            "org.apache.http.impl.client.HttpClients",
            "java.net.HttpURLConnection",
            "java.net.URL",
        )

    // Synchronous file I/O types
    val FILE_IO_TYPES =
        setOf(
            "java.io.FileInputStream",
            "java.io.FileOutputStream",
            "java.io.FileReader",
            "java.io.FileWriter",
            "java.io.RandomAccessFile",
            "java.io.BufferedReader",
            "java.io.BufferedWriter",
        )

    // Types that indicate async patterns are being used
    val ASYNC_TYPES =
        setOf(
            "ratpack.exec.Blocking",
            "ratpack.exec.Promise",
            "ratpack.exec.Operation",
        )
}

/**
 * Detects anti-patterns in Ratpack handlers and related classes.
 *
 * Detection is based on field types, constructor parameters, and method signatures.
 * This is a heuristic approach since we cannot analyze method bodies without source code.
 */
class AntiPatternDetector(
    private val classGraphProvider: ClassGraphProvider,
) {
    private val logger = LoggerFactory.getLogger(AntiPatternDetector::class.java)

    // Lazy-initialized RatpackDetector for handler checking
    private val ratpackDetector by lazy { RatpackDetector(classGraphProvider) }

    // Bounded LRU cache of known handler FQNs (max 1000 entries)
    private val handlerCache =
        object : LinkedHashMap<String, Boolean>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 1000
        }

    /**
     * Check if a type matches any of the indicator types.
     * Uses exact FQN matching or suffix matching with proper boundaries.
     */
    private fun typeMatchesAny(
        type: String,
        indicators: Set<String>,
    ): Boolean =
        indicators.any { indicator ->
            type == indicator || type.endsWith(".$indicator")
        }

    /**
     * Check if a class is a Ratpack handler.
     */
    private fun isHandler(fqn: String): Boolean =
        handlerCache.getOrPut(fqn) {
            ratpackDetector.getHandlerDetail(fqn) != null
        }

    /**
     * Analyze a specific class for anti-patterns.
     */
    fun analyzeClass(fqn: String): List<AntiPatternInstance> {
        val classInfo = classGraphProvider.getClass(fqn) ?: return emptyList()
        return analyzeClassForAntiPatterns(classInfo, fqn)
    }

    /**
     * Internal method to analyze a ClassInfo for anti-patterns.
     */
    private fun analyzeClassForAntiPatterns(
        classInfo: codelens.core.model.ClassInfo,
        fqn: String,
    ): List<AntiPatternInstance> {
        val antiPatterns = mutableListOf<AntiPatternInstance>()

        // Check if this is a handler - some patterns are more severe in handlers
        val isHandler = isHandler(fqn)

        // Collect all type references from fields and methods
        val allTypes = collectAllTypes(classInfo)

        // Check if class uses async patterns
        val usesAsyncPatterns =
            allTypes.any { type ->
                typeMatchesAny(type, AntiPatternIndicators.ASYNC_TYPES)
            }

        // Check for blocking JDBC usage
        antiPatterns.addAll(detectBlockingJdbc(classInfo, fqn, isHandler, usesAsyncPatterns))

        // Check for blocking HTTP clients
        antiPatterns.addAll(detectBlockingHttpClient(classInfo, fqn, isHandler))

        // Check for synchronous file I/O
        antiPatterns.addAll(detectSynchronousFileIO(classInfo, fqn, isHandler, usesAsyncPatterns))

        // Check for console logging (PrintStream usage)
        antiPatterns.addAll(detectConsoleLogging(classInfo, fqn))

        return antiPatterns
    }

    /**
     * Collect all type references from a class.
     */
    private fun collectAllTypes(classInfo: codelens.core.model.ClassInfo): Set<String> {
        val types = mutableSetOf<String>()

        // Add field types
        classInfo.fields.forEach { types.add(it.type) }

        // Add method return types and parameter types
        classInfo.methods.forEach { method ->
            types.add(method.returnType)
            method.parameters.forEach { types.add(it.type) }
        }

        // Add constructor parameter types
        classInfo.constructors.forEach { constructor ->
            constructor.parameters.forEach { types.add(it.type) }
        }

        return types
    }

    /**
     * Detect JDBC usage without async wrappers.
     */
    private fun detectBlockingJdbc(
        classInfo: codelens.core.model.ClassInfo,
        fqn: String,
        isHandler: Boolean,
        usesAsyncPatterns: Boolean,
    ): List<AntiPatternInstance> {
        val results = mutableListOf<AntiPatternInstance>()

        // Check fields for JDBC types
        val jdbcFields =
            classInfo.fields.filter { field ->
                typeMatchesAny(field.type, AntiPatternIndicators.JDBC_TYPES)
            }

        // Check constructor params for JDBC types
        val jdbcParams =
            classInfo.constructors
                .flatMap { it.parameters }
                .filter { param -> typeMatchesAny(param.type, AntiPatternIndicators.JDBC_TYPES) }

        if (jdbcFields.isNotEmpty() || jdbcParams.isNotEmpty()) {
            // If using JDBC but not using async patterns, flag it
            if (!usesAsyncPatterns) {
                val fieldNames = jdbcFields.map { it.name }
                results.add(
                    AntiPatternInstance(
                        type = AntiPatternType.BLOCKING_JDBC,
                        severity = if (isHandler) AntiPatternSeverity.CRITICAL else AntiPatternSeverity.ERROR,
                        classFqn = fqn,
                        methodName = null,
                        confidence = 0.8,
                        reason =
                            "JDBC types (${fieldNames.ifEmpty { jdbcParams.map { it.type.substringAfterLast(".") } }.joinToString()}) " +
                                "are used without visible Blocking.get() usage. This may block the event loop.",
                        recommendation = "Wrap JDBC calls in Blocking.get { ... } to move them off the compute thread.",
                        fixExample =
                            """
                            |// Before (blocking):
                            |val result = connection.prepareStatement(sql).executeQuery()
                            |
                            |// After (non-blocking):
                            |Blocking.get {
                            |    connection.prepareStatement(sql).executeQuery()
                            |}.then { result ->
                            |    // handle result
                            |}
                            """.trimMargin(),
                    ),
                )
            }
        }

        return results
    }

    /**
     * Detect blocking HTTP client usage.
     */
    private fun detectBlockingHttpClient(
        classInfo: codelens.core.model.ClassInfo,
        fqn: String,
        isHandler: Boolean,
    ): List<AntiPatternInstance> {
        val results = mutableListOf<AntiPatternInstance>()

        // Check fields for blocking HTTP types
        val blockingHttpFields =
            classInfo.fields.filter { field ->
                typeMatchesAny(field.type, AntiPatternIndicators.BLOCKING_HTTP_TYPES)
            }

        // Check constructor params for blocking HTTP types
        val blockingHttpParams =
            classInfo.constructors
                .flatMap { it.parameters }
                .filter { param -> typeMatchesAny(param.type, AntiPatternIndicators.BLOCKING_HTTP_TYPES) }

        if (blockingHttpFields.isNotEmpty() || blockingHttpParams.isNotEmpty()) {
            // Check if also using Ratpack's HttpClient
            val ratpackHttpClientType = setOf("ratpack.http.client.HttpClient")
            val usesRatpackHttpClient =
                classInfo.fields.any { field ->
                    typeMatchesAny(field.type, ratpackHttpClientType)
                } ||
                    classInfo.constructors
                        .flatMap { it.parameters }
                        .any { typeMatchesAny(it.type, ratpackHttpClientType) }

            if (!usesRatpackHttpClient) {
                val fieldNames = blockingHttpFields.map { it.name }
                results.add(
                    AntiPatternInstance(
                        type = AntiPatternType.BLOCKING_HTTP_CLIENT,
                        severity = if (isHandler) AntiPatternSeverity.ERROR else AntiPatternSeverity.WARNING,
                        classFqn = fqn,
                        methodName = null,
                        confidence = 0.85,
                        reason =
                            "Blocking HTTP client (${fieldNames.ifEmpty {
                                blockingHttpParams.map {
                                    it.type.substringAfterLast(
                                        ".",
                                    )
                                }
                            }.joinToString()}) " +
                                "detected. Apache HttpClient and java.net.URL perform blocking I/O.",
                        recommendation = "Use Ratpack's HttpClient which returns Promises for async HTTP calls.",
                        fixExample =
                            """
                            |// Before (blocking with Apache HttpClient):
                            |val response = httpClient.execute(request)
                            |
                            |// After (non-blocking with Ratpack HttpClient):
                            |httpClient.get(uri).then { response ->
                            |    // handle response
                            |}
                            """.trimMargin(),
                    ),
                )
            }
        }

        return results
    }

    /**
     * Detect synchronous file I/O usage.
     */
    private fun detectSynchronousFileIO(
        classInfo: codelens.core.model.ClassInfo,
        fqn: String,
        isHandler: Boolean,
        usesAsyncPatterns: Boolean,
    ): List<AntiPatternInstance> {
        val results = mutableListOf<AntiPatternInstance>()

        // Check fields for file I/O types
        val fileIoFields =
            classInfo.fields.filter { field ->
                typeMatchesAny(field.type, AntiPatternIndicators.FILE_IO_TYPES)
            }

        // Check constructor params for file I/O types
        val fileIoParams =
            classInfo.constructors
                .flatMap { it.parameters }
                .filter { param -> typeMatchesAny(param.type, AntiPatternIndicators.FILE_IO_TYPES) }

        if ((fileIoFields.isNotEmpty() || fileIoParams.isNotEmpty()) && !usesAsyncPatterns) {
            val fieldNames = fileIoFields.map { it.name }
            results.add(
                AntiPatternInstance(
                    type = AntiPatternType.SYNCHRONOUS_FILE_IO,
                    severity = if (isHandler) AntiPatternSeverity.ERROR else AntiPatternSeverity.WARNING,
                    classFqn = fqn,
                    methodName = null,
                    confidence = 0.75,
                    reason =
                        "Synchronous file I/O types (${fieldNames.ifEmpty {
                            fileIoParams.map {
                                it.type.substringAfterLast(
                                    ".",
                                )
                            }
                        }.joinToString()}) " +
                            "detected without visible Blocking usage.",
                    recommendation = "Wrap file I/O in Blocking.get { ... } or use async file APIs.",
                    fixExample =
                        """
                        |// Before (blocking):
                        |val content = Files.readAllBytes(path)
                        |
                        |// After (non-blocking):
                        |Blocking.get {
                        |    Files.readAllBytes(path)
                        |}.then { content ->
                        |    // handle content
                        |}
                        """.trimMargin(),
                ),
            )
        }

        return results
    }

    /**
     * Detect direct console output (System.out/err via PrintStream).
     */
    private fun detectConsoleLogging(
        classInfo: codelens.core.model.ClassInfo,
        fqn: String,
    ): List<AntiPatternInstance> {
        val results = mutableListOf<AntiPatternInstance>()

        // Check if any field is a PrintStream (but not named as logger)
        val printStreamType = setOf("java.io.PrintStream")
        val printStreamFields =
            classInfo.fields.filter { field ->
                typeMatchesAny(field.type, printStreamType) &&
                    !field.name.lowercase().contains("log")
            }

        // Check method parameters for PrintStream
        val printStreamParams =
            classInfo.methods
                .flatMap { it.parameters }
                .filter { param -> typeMatchesAny(param.type, printStreamType) }

        if (printStreamFields.isNotEmpty() || printStreamParams.isNotEmpty()) {
            // Check if class has a logger (using suffix check for Logger types)
            val hasLogger =
                classInfo.fields.any { field ->
                    field.type.endsWith("Logger") || field.type.endsWith("Log")
                }

            if (!hasLogger) {
                results.add(
                    AntiPatternInstance(
                        type = AntiPatternType.CONSOLE_LOGGING,
                        severity = AntiPatternSeverity.INFO,
                        classFqn = fqn,
                        methodName = null,
                        confidence = 0.6,
                        reason =
                            "PrintStream usage detected without a logger field. " +
                                "Direct console output is synchronous and not captured by logging frameworks.",
                        recommendation = "Use SLF4J or another logging framework for proper log management.",
                        fixExample =
                            """
                            |// Before:
                            |System.out.println("Processing request")
                            |
                            |// After:
                            |private val logger = LoggerFactory.getLogger(MyClass::class.java)
                            |logger.info("Processing request")
                            """.trimMargin(),
                    ),
                )
            }
        }

        return results
    }

    /**
     * Get project-wide anti-pattern summary.
     */
    fun getProjectSummary(
        severityFilter: AntiPatternSeverity? = null,
        typeFilter: AntiPatternType? = null,
        includeLibraries: Boolean = false,
    ): AntiPatternSummary {
        val allInstances = mutableListOf<AntiPatternInstance>()
        val filter = ClassFilter(includeLibraries = includeLibraries)
        val classes = classGraphProvider.listClasses(filter)

        for (classSummary in classes) {
            val classAntiPatterns = analyzeClass(classSummary.fqn)
            allInstances.addAll(classAntiPatterns)
        }

        // Apply filters
        val filteredInstances =
            allInstances.filter { instance ->
                (severityFilter == null || instance.severity == severityFilter) &&
                    (typeFilter == null || instance.type == typeFilter)
            }

        // Build count by type
        val countByType =
            filteredInstances
                .groupBy { it.type }
                .mapValues { it.value.size }

        // Build count by severity
        val countBySeverity =
            filteredInstances
                .groupBy { it.severity }
                .mapValues { it.value.size }

        // Find worst offenders (top 10 classes by anti-pattern count)
        val classCounts =
            filteredInstances
                .groupBy { it.classFqn }
                .map { (classFqn, instances) ->
                    ClassAntiPatternCount(
                        classFqn = classFqn,
                        count = instances.size,
                        criticalCount = instances.count { it.severity == AntiPatternSeverity.CRITICAL },
                        errorCount = instances.count { it.severity == AntiPatternSeverity.ERROR },
                    )
                }.sortedWith(
                    compareBy(
                        { -it.criticalCount },
                        { -it.errorCount },
                        { -it.count },
                    ),
                ).take(10)

        return AntiPatternSummary(
            instances = filteredInstances,
            countByType = countByType,
            countBySeverity = countBySeverity,
            worstOffenders = classCounts,
            totalCount = filteredInstances.size,
        )
    }
}
