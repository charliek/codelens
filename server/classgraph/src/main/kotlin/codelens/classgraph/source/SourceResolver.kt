package codelens.classgraph.source

import codelens.core.model.ClassInfo
import codelens.core.model.ClassSource
import codelens.core.model.source.*
import java.io.File

/**
 * Resolves source code for analyzed classes.
 */
class SourceResolver(
    private val sourceRoots: List<SourceRootInfo>,
    private val classes: Map<String, ClassInfo>,
) {
    private val methodExtractor = MethodExtractor()

    /**
     * Resolves source code for a class by FQN.
     *
     * @param fqn Fully qualified class name
     * @return SourceInfo or error
     */
    fun resolveClass(fqn: String): Result<SourceInfo> {
        // 1. Check if class exists and is a project class
        val classInfo =
            classes[fqn]
                ?: return Result.failure(
                    SourceResolutionException(
                        fqn,
                        SourceResolutionErrorReason.CLASS_NOT_FOUND,
                        "Class not found in scan results: $fqn",
                    ),
                )

        if (classInfo.source == ClassSource.LIBRARY) {
            return Result.failure(
                SourceResolutionException(
                    fqn,
                    SourceResolutionErrorReason.LIBRARY_CLASS,
                    "Source not available for library class: $fqn",
                ),
            )
        }

        if (classInfo.source == ClassSource.JDK) {
            return Result.failure(
                SourceResolutionException(
                    fqn,
                    SourceResolutionErrorReason.JDK_CLASS,
                    "Source not available for JDK class: $fqn",
                ),
            )
        }

        // 2. Convert FQN to path segment
        val pathSegment = fqnToPathSegment(fqn)

        // 3. Search source roots (prioritize main sources over test sources)
        val sortedRoots =
            sourceRoots.sortedWith(
                compareBy(
                    { if (it.sourceSet == "main") 0 else 1 },
                    { it.module },
                ),
            )

        for (sourceRoot in sortedRoots) {
            val extension = if (sourceRoot.language == "kotlin") ".kt" else ".java"
            val sourceFile = File(sourceRoot.path.absolutePath, "$pathSegment$extension")

            if (sourceFile.exists() && sourceFile.isFile) {
                val content = sourceFile.readText()
                return Result.success(
                    SourceInfo(
                        fqn = fqn,
                        filePath = sourceFile.absolutePath,
                        language =
                            if (sourceRoot.language == "kotlin") {
                                SourceLanguage.KOTLIN
                            } else {
                                SourceLanguage.JAVA
                            },
                        content = content,
                        lineCount = content.lines().size,
                        module = sourceRoot.module.takeIf { it != ":" },
                    ),
                )
            }
        }

        return Result.failure(
            SourceResolutionException(
                fqn,
                SourceResolutionErrorReason.FILE_NOT_FOUND,
                "Source file not found for class: $fqn. Searched ${sourceRoots.size} source roots.",
            ),
        )
    }

    /**
     * Resolves source code for a specific method.
     *
     * @param fqn Fully qualified class name
     * @param methodName Method name
     * @param parameterTypes Optional list of parameter types for disambiguation
     * @param contextLines Number of context lines to include before/after
     * @return MethodSourceInfo or error
     */
    fun resolveMethod(
        fqn: String,
        methodName: String,
        parameterTypes: List<String>? = null,
        contextLines: Int = 0,
    ): Result<MethodSourceInfo> {
        // 1. Get class source
        val sourceResult = resolveClass(fqn)
        if (sourceResult.isFailure) {
            return Result.failure(sourceResult.exceptionOrNull()!!)
        }
        val sourceInfo = sourceResult.getOrThrow()

        // 2. Find method in class info
        val classInfo = classes[fqn]!!
        val method =
            classInfo.methods.find { m ->
                m.name == methodName &&
                    (
                        parameterTypes == null ||
                            m.parameters.map { simplifyType(it.type) } == parameterTypes.map { simplifyType(it) }
                    )
            } ?: return Result.failure(
                SourceResolutionException(
                    fqn,
                    SourceResolutionErrorReason.METHOD_NOT_FOUND,
                    "Method not found: $methodName in class $fqn",
                ),
            )

        // 3. Extract method source using signature search
        // Note: Line number extraction could be added if we enhance MethodInfo with bytecode debug info
        val lines = sourceInfo.content.lines()

        val extraction =
            methodExtractor.extractBySignature(
                lines,
                methodName,
                method.parameters.map { it.type },
                sourceInfo.language,
            )

        if (extraction == null) {
            return Result.failure(
                SourceResolutionException(
                    fqn,
                    SourceResolutionErrorReason.METHOD_NOT_FOUND,
                    "Could not extract method source for: $methodName",
                ),
            )
        }

        // 4. Add context if requested
        val contextBefore =
            if (contextLines > 0) {
                lines
                    .subList(
                        maxOf(0, extraction.startLine - 1 - contextLines),
                        extraction.startLine - 1,
                    ).joinToString("\n")
            } else {
                null
            }

        val contextAfter =
            if (contextLines > 0) {
                lines
                    .subList(
                        extraction.endLine,
                        minOf(lines.size, extraction.endLine + contextLines),
                    ).joinToString("\n")
            } else {
                null
            }

        return Result.success(
            MethodSourceInfo(
                classFqn = fqn,
                methodName = methodName,
                signature = buildMethodSignature(method),
                content = extraction.content,
                startLine = extraction.startLine,
                endLine = extraction.endLine,
                contextBefore = contextBefore,
                contextAfter = contextAfter,
            ),
        )
    }

    /**
     * Converts FQN to file path segment, handling nested classes.
     */
    private fun fqnToPathSegment(fqn: String): String {
        // Handle nested classes: com.example.Outer$Inner -> com/example/Outer
        val outerClass = fqn.substringBefore('$')
        return outerClass.replace('.', '/')
    }

    /**
     * Simplifies a type name by removing generics and package prefix.
     */
    private fun simplifyType(type: String): String = type.substringBefore('<').substringAfterLast('.')

    private fun buildMethodSignature(method: codelens.core.model.MethodInfo): String {
        val params = method.parameters.joinToString(", ") { "${it.name}: ${simplifyType(it.type)}" }
        return "${method.name}($params): ${simplifyType(method.returnType)}"
    }
}
