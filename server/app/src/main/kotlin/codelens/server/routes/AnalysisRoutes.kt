package codelens.server.routes

import codelens.core.model.*
import codelens.server.services.AnalysisService
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Helper to extract FQN from path parameters and respond with error if missing.
 * Returns null if FQN is missing (and responds with 400), otherwise returns the FQN.
 */
private suspend fun RoutingContext.getFqnOrRespond(
    paramName: String = "fqn",
    errorMessage: String = "Class FQN is required",
): String? {
    val fqn = call.parameters.getAll(paramName)?.joinToString(".")
    if (fqn.isNullOrBlank()) {
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(
                code = 400,
                type = "BadRequest",
                message = errorMessage,
            ),
        )
        return null
    }
    return fqn
}

/**
 * Parses and validates the shared `page`/`size` query parameters. Responds 400 and
 * returns null on out-of-range input — so `size=0` can't divide-by-zero and a
 * negative page can't produce a bad slice. Callers use `?: return@get`.
 */
private suspend fun RoutingContext.pageParamsOrRespond(): Pair<Int, Int>? {
    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
    val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 50
    if (page < 0 || size < 1) {
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(
                code = 400,
                type = "BadRequest",
                message = "page must be >= 0 and size must be >= 1 (got page=$page, size=$size)",
            ),
        )
        return null
    }
    return page to size
}

/**
 * Overflow-safe page slice over an already-sorted list, returning the page's items
 * and the total page count. The index math is done in Long so a large [page] can't
 * overflow Int into a negative `subList` bound (assumes [page] >= 0 and [pageSize]
 * >= 1, as enforced by [pageParamsOrRespond]).
 */
private fun <T> List<T>.pageOf(
    page: Int,
    pageSize: Int,
): Pair<List<T>, Int> {
    val total = this.size
    val totalPages = if (total == 0) 1 else ((total.toLong() + pageSize - 1) / pageSize).toInt()
    val start = page.toLong() * pageSize
    val slice =
        if (start < total) {
            subList(start.toInt(), minOf(start + pageSize, total.toLong()).toInt())
        } else {
            emptyList()
        }
    return slice to totalPages
}

/**
 * Routes for bytecode analysis endpoints.
 */
fun Route.analysisRoutes(analysisService: AnalysisService) {
    route("/api/v1") {
        /**
         * GET /api/v1/stats
         * Get scan statistics.
         */
        get("/stats") {
            val stats = analysisService.getStatistics()
            if (stats != null) {
                call.respond(stats)
            } else {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse(
                        code = 503,
                        type = "ScanNotReady",
                        message = "Scan has not completed yet",
                    ),
                )
            }
        }

        /**
         * GET /api/v1/classes
         * List classes with optional filtering.
         *
         * Query parameters:
         * - package: Filter by package pattern (supports * wildcard)
         * - name: Filter by class name pattern (supports * wildcard)
         * - annotation: Filter to classes with this annotation
         * - extends: Filter to classes extending this class
         * - implements: Filter to classes implementing this interface
         * - interfaces: Only show interfaces (true/false)
         * - includeLibraries: Include library classes (default: false)
         * - page: Page number (0-based, default: 0)
         * - size: Page size (default: 50)
         */
        get("/classes") {
            val packagePattern = call.request.queryParameters["package"]
            val namePattern = call.request.queryParameters["name"]
            val annotation = call.request.queryParameters["annotation"]
            val extendsClass = call.request.queryParameters["extends"]
            val implementsInterface = call.request.queryParameters["implements"]
            val onlyInterfaces = call.request.queryParameters["interfaces"]?.toBoolean() ?: false
            val includeLibraries = call.request.queryParameters["includeLibraries"]?.toBoolean() ?: false
            val (page, size) = pageParamsOrRespond() ?: return@get

            val filter =
                ClassFilter(
                    packagePattern = packagePattern,
                    namePattern = namePattern,
                    hasAnnotation = annotation,
                    extendsClass = extendsClass,
                    implementsInterface = implementsInterface,
                    onlyInterfaces = onlyInterfaces,
                    includeLibraries = includeLibraries,
                )

            val allClasses = analysisService.listClasses(filter)
            val totalCount = allClasses.size
            val (pagedClasses, totalPages) = allClasses.pageOf(page, size)

            val response =
                ClassListResponse(
                    classes = pagedClasses,
                    totalCount = totalCount,
                    page = page,
                    pageSize = size,
                    totalPages = totalPages,
                    appliedFilter =
                        ClassFilterSummary(
                            packagePattern = packagePattern,
                            namePattern = namePattern,
                            source = if (includeLibraries) null else "PROJECT",
                            hasAnnotation = annotation,
                            extendsClass = extendsClass,
                            implementsInterface = implementsInterface,
                        ),
                )

            call.respond(response)
        }

        /**
         * GET /api/v1/classes/{fqn}
         * Get full details for a specific class.
         */
        get("/classes/{fqn...}") {
            val fqn = getFqnOrRespond() ?: return@get

            val classInfo = analysisService.getClass(fqn)
            if (classInfo != null) {
                call.respond(ClassDetailResponse(classInfo = classInfo))
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse(
                        code = 404,
                        type = "NotFound",
                        message = "Class not found: $fqn",
                    ),
                )
            }
        }

        /**
         * GET /api/v1/implementations/{fqn}
         * Find all implementations of an interface or subclasses of a class.
         *
         * Query parameters:
         * - includeLibraries: Include library classes (default: false)
         */
        get("/implementations/{fqn...}") {
            val fqn = getFqnOrRespond() ?: return@get

            val includeLibraries = call.request.queryParameters["includeLibraries"]?.toBoolean() ?: false

            val (direct, indirect) = analysisService.getImplementations(fqn, includeLibraries)

            call.respond(
                ImplementationsResponse(
                    targetClass = fqn,
                    directImplementations = direct,
                    indirectImplementations = indirect,
                    totalCount = direct.size + indirect.size,
                ),
            )
        }

        /**
         * GET /api/v1/hierarchy/{fqn}
         * Get the class hierarchy for a given class.
         */
        get("/hierarchy/{fqn...}") {
            val fqn = getFqnOrRespond() ?: return@get

            val hierarchy = analysisService.getHierarchy(fqn)
            if (hierarchy != null) {
                call.respond(HierarchyResponse(targetClass = fqn, hierarchy = hierarchy))
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse(
                        code = 404,
                        type = "NotFound",
                        message = "Class not found: $fqn",
                    ),
                )
            }
        }

        /**
         * GET /api/v1/dependencies/{fqn}
         * Get dependencies for a class.
         *
         * Query parameters:
         * - includeLibraries: Include library classes (default: false)
         */
        get("/dependencies/{fqn...}") {
            val fqn = getFqnOrRespond() ?: return@get

            val includeLibraries = call.request.queryParameters["includeLibraries"]?.toBoolean() ?: false

            val (outgoing, incoming) = analysisService.getDependencies(fqn, includeLibraries)

            call.respond(
                DependenciesResponse(
                    targetClass = fqn,
                    outgoing = outgoing,
                    incoming = incoming,
                ),
            )
        }

        /**
         * GET /api/v1/annotations/usages/{fqn}
         * Find every place an annotation is applied, with the matched annotation's
         * typed attribute values inline.
         *
         * Query parameters:
         * - scope: Which declaration sites to scan — CLASS, METHOD, FIELD, PARAM, or
         *   ALL (default). METHOD also surfaces constructors (target=CONSTRUCTOR,
         *   `<init>`). Matching is meta-expanded (e.g. `@RequestMapping` matches
         *   `@GetMapping` methods, returning the synthesized instance's attributes).
         * - includeLibraries: Include library classes (default: false)
         * - page / size: Pagination over the (scoped, sorted) usages
         */
        get("/annotations/usages/{fqn...}") {
            val fqn = getFqnOrRespond(errorMessage = "Annotation FQN is required") ?: return@get
            val includeLibraries = call.request.queryParameters["includeLibraries"]?.toBoolean() ?: false
            val (page, size) = pageParamsOrRespond() ?: return@get

            val scopeParam = call.request.queryParameters["scope"]?.takeUnless { it.isBlank() }
            val scope =
                if (scopeParam != null) {
                    try {
                        AnnotationScope.valueOf(scopeParam.uppercase())
                    } catch (e: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(
                                code = 400,
                                type = "BadRequest",
                                message = "Invalid annotation scope: $scopeParam. Valid values: ${AnnotationScope.entries.joinToString()}",
                            ),
                        )
                        return@get
                    }
                } else {
                    AnnotationScope.ALL
                }

            val all = analysisService.getAnnotationUsages(fqn, scope, includeLibraries)
            // Breakdown over the full scoped result (before pagination), like xref's countsByKind.
            // Emit keys in target-declaration order so the raw JSON object is byte-stable (the
            // usages array is deliberately sorted; keep its sibling map deterministic too).
            val rawCounts = all.groupingBy { it.target }.eachCount()
            val countsByTarget = AnnotationUsageTarget.entries.filter { it in rawCounts }.associate { it.name to rawCounts.getValue(it) }

            // Total order so pagination and golden output are stable: ConcurrentHashMap
            // iteration is unordered, and meta-expansion can yield repeated (site, fqn)
            // matches — the trailing annotation keys break any remaining ties.
            val sorted =
                all.sortedWith(
                    compareBy(
                        { it.classFqn },
                        { it.target.ordinal },
                        { it.method ?: "" },
                        { it.descriptor ?: "" },
                        { it.field ?: "" },
                        { it.parameterIndex ?: -1 },
                        { it.parameterName ?: "" },
                        { it.annotation.type },
                        { it.annotation.parameters.toString() },
                    ),
                )

            val totalCount = sorted.size
            val (pageSlice, totalPages) = sorted.pageOf(page, size)

            call.respond(
                AnnotationUsagesResponse(
                    annotationFqn = fqn,
                    usages = pageSlice,
                    totalCount = totalCount,
                    page = page,
                    pageSize = size,
                    totalPages = totalPages,
                    countsByTarget = countsByTarget,
                    appliedFilter =
                        AnnotationUsagesFilterSummary(
                            includeLibraries = includeLibraries,
                            scope = scope,
                        ),
                ),
            )
        }

        /**
         * GET /api/v1/methods
         * Search methods across all classes.
         *
         * Query parameters:
         * - name: Filter by method name pattern (supports * wildcard)
         * - returnType: Filter by return type FQN
         * - annotation: Filter to methods with this annotation
         * - inClass: Filter by containing class FQN
         * - inPackage: Filter by containing package pattern
         * - includeLibraries: Include library classes (default: false)
         * - page: Page number (0-based, default: 0)
         * - size: Page size (default: 50)
         */
        get("/methods") {
            val namePattern = call.request.queryParameters["name"]
            val returnType = call.request.queryParameters["returnType"]
            val annotation = call.request.queryParameters["annotation"]
            val inClass = call.request.queryParameters["inClass"]
            val inPackage = call.request.queryParameters["inPackage"]
            val includeLibraries = call.request.queryParameters["includeLibraries"]?.toBoolean() ?: false
            val (page, size) = pageParamsOrRespond() ?: return@get

            val filter =
                MethodFilter(
                    namePattern = namePattern,
                    returnType = returnType,
                    hasAnnotation = annotation,
                    inClass = inClass,
                    inPackage = inPackage,
                    includeLibraries = includeLibraries,
                )

            val allMethods = analysisService.searchMethods(filter)
            val totalCount = allMethods.size
            val (pagedMethods, totalPages) = allMethods.pageOf(page, size)

            call.respond(
                MethodSearchResponse(
                    methods = pagedMethods,
                    totalCount = totalCount,
                    page = page,
                    pageSize = size,
                    totalPages = totalPages,
                ),
            )
        }

        /**
         * GET /api/v1/calls/{fqn}
         * Extract the invocations a class's method bodies make (raw bytecode
         * call-site facts). Uses the same multi-segment FQN capture as the
         * sibling class endpoints.
         *
         * Query parameters:
         * - method: Only scan this method (others are omitted)
         * - descriptor: Exact JVM descriptor to disambiguate overloads
         *   (only honored together with `method`)
         * - inMethodsReturning: Keep only call-sites inside enclosing methods
         *   whose (erased) return type matches this FQN
         * - inMethodsAnnotated: Keep only call-sites inside enclosing methods
         *   carrying this annotation (meta-expanded); ANDed with
         *   `inMethodsReturning` when both are set. Both compose with `method`.
         *
         * Without `method`, methods that make no calls are omitted. With it, a
         * matching method is returned even when it makes no calls (one entry,
         * empty `calls`); an unknown method yields no entries. The two
         * `inMethods*` filters scope to direct call-sites in matching methods by
         * the enclosing method's declared signature (not `lambda$…`/transitive).
         */
        get("/calls/{fqn...}") {
            val fqn = getFqnOrRespond() ?: return@get
            // Treat blank query values as absent so `?method=` doesn't filter
            // for a method literally named "".
            val method = call.request.queryParameters["method"]?.takeUnless { it.isBlank() }
            // descriptor disambiguates a named method; the whole-class view
            // ignores it, so only forward it alongside a method.
            val descriptor =
                if (method != null) {
                    call.request.queryParameters["descriptor"]?.takeUnless { it.isBlank() }
                } else {
                    null
                }
            val inMethodsReturning = call.request.queryParameters["inMethodsReturning"]?.takeUnless { it.isBlank() }
            val inMethodsAnnotated = call.request.queryParameters["inMethodsAnnotated"]?.takeUnless { it.isBlank() }
            respondCalls(analysisService.getCalls(fqn, method, descriptor, inMethodsReturning, inMethodsAnnotated))
        }

        /**
         * GET /api/v1/xref/{typeFqn}
         * Find everything across the project that references a type (inverse
         * cross-reference), grouped by reference kind.
         *
         * Query parameters:
         * - includeLibraries: Include references from library classes (default: false)
         * - kind: Restrict to one reference kind (EXTENDS, IMPLEMENTS, FIELD,
         *   PARAM, RETURN, ANNOTATION, INSTANTIATION, CALL_RECEIVER)
         * - scopeImplementing: Only count references from classes that implement
         *   (or extend) this type
         * - page / size: Pagination over the (kind-filtered) references
         */
        get("/xref/{typeFqn...}") {
            val typeFqn = getFqnOrRespond("typeFqn", "Type FQN is required") ?: return@get
            val includeLibraries = call.request.queryParameters["includeLibraries"]?.toBoolean() ?: false
            val scopeImplementing = call.request.queryParameters["scopeImplementing"]?.takeUnless { it.isBlank() }
            val (page, size) = pageParamsOrRespond() ?: return@get
            val kindParam = call.request.queryParameters["kind"]?.takeUnless { it.isBlank() }

            val kind =
                if (kindParam != null) {
                    try {
                        XrefKind.valueOf(kindParam.uppercase())
                    } catch (e: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(
                                code = 400,
                                type = "BadRequest",
                                message = "Invalid xref kind: $kindParam. Valid values: ${XrefKind.entries.joinToString()}",
                            ),
                        )
                        return@get
                    }
                } else {
                    null
                }

            val all = analysisService.getReferencesToType(typeFqn, includeLibraries, scopeImplementing)
            // Breakdown by kind over the full result (before the kind filter).
            val countsByKind = all.groupingBy { it.kind.name }.eachCount()

            val filtered = if (kind != null) all.filter { it.kind == kind } else all
            val countsByPackage = filtered.groupingBy { it.fromFqn.substringBeforeLast('.', "") }.eachCount()

            // Deterministic order so pagination and golden output are stable.
            val sorted =
                filtered.sortedWith(
                    compareBy(
                        { it.fromFqn },
                        { it.kind.ordinal },
                        { it.member ?: "" },
                        { it.lineNumber ?: -1 },
                        { it.detail ?: "" },
                    ),
                )

            val totalCount = sorted.size
            val (pageSlice, totalPages) = sorted.pageOf(page, size)

            call.respond(
                XrefResponse(
                    typeFqn = typeFqn,
                    references = pageSlice,
                    totalCount = totalCount,
                    page = page,
                    pageSize = size,
                    totalPages = totalPages,
                    countsByKind = countsByKind,
                    countsByPackage = countsByPackage,
                    appliedFilter =
                        XrefFilterSummary(
                            includeLibraries = includeLibraries,
                            kind = kindParam,
                            scopeImplementing = scopeImplementing,
                        ),
                ),
            )
        }

        /**
         * GET /api/v1/graph
         * The project-wide dependency graph (project classes + project-to-project
         * dependencies).
         *
         * Query parameters:
         * - format: json (default) or dot
         */
        get("/graph") {
            when (call.request.queryParameters["format"]?.lowercase() ?: "json") {
                "dot" -> call.respondText(analysisService.getProjectGraphDot(), ContentType.Text.Plain)
                else -> call.respond(analysisService.getProjectGraph())
            }
        }

        /**
         * GET /api/v1/graph/foundation
         * Foundation classes — the most depended-on project classes.
         *
         * Query parameters:
         * - minDependents: Minimum in-degree to qualify (default: 2)
         */
        get("/graph/foundation") {
            val minDependents = call.request.queryParameters["minDependents"]?.toIntOrNull() ?: 2
            val foundation = analysisService.getFoundationClasses(minDependents)
            call.respond(FoundationResponse(foundationClasses = foundation, count = foundation.size))
        }
    }
}

/**
 * Wraps a [CallSiteList] in the API response shape, computing the total call count.
 */
private suspend fun RoutingContext.respondCalls(result: CallSiteList) {
    call.respond(
        CallsResponse(
            fqn = result.fqn,
            methods = result.methods,
            totalCalls = result.methods.sumOf { it.calls.size },
        ),
    )
}
