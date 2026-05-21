package codelens.classgraph.source

import codelens.core.model.source.SourceLanguage

/**
 * Extracts method source code from file content.
 */
class MethodExtractor {
    /**
     * Result of method extraction.
     */
    data class Extraction(
        val content: String,
        val startLine: Int, // 1-based
        val endLine: Int, // 1-based
    )

    /**
     * Extracts a method starting at a known line number.
     *
     * @param lines All lines of the source file
     * @param startLine 1-based starting line number (from bytecode debug info)
     * @param language Source language
     * @return Extraction result or null if extraction fails
     */
    fun extractByLineNumber(
        lines: List<String>,
        startLine: Int,
        language: SourceLanguage,
    ): Extraction? {
        if (startLine < 1 || startLine > lines.size) return null

        // Try to find method declaration by walking back from startLine
        var actualStart = findMethodDeclarationStart(lines, startLine - 1, language)
        if (actualStart < 0) actualStart = startLine - 1

        // Find method end by tracking brace nesting
        val endLine = findMethodEnd(lines, actualStart)

        return Extraction(
            content = lines.subList(actualStart, endLine).joinToString("\n"),
            startLine = actualStart + 1, // Convert to 1-based
            endLine = endLine,
        )
    }

    /**
     * Extracts a method by searching for its signature.
     *
     * @param lines All lines of the source file
     * @param methodName Method name to search for
     * @param paramTypes Parameter types for disambiguation
     * @param language Source language
     * @return Extraction result or null if method not found
     */
    fun extractBySignature(
        lines: List<String>,
        methodName: String,
        paramTypes: List<String>,
        language: SourceLanguage,
    ): Extraction? {
        // Build pattern based on language
        val methodPattern =
            when (language) {
                SourceLanguage.KOTLIN -> Regex("""(fun\s+)?$methodName\s*\(""")
                SourceLanguage.JAVA -> Regex("""\b$methodName\s*\(""")
                else -> Regex("""\b$methodName\s*\(""")
            }

        // Search for method declaration
        for (i in lines.indices) {
            val line = lines[i]
            if (methodPattern.containsMatchIn(line)) {
                // Check if this looks like a method declaration (not a call)
                if (isMethodDeclaration(lines, i, methodName, language)) {
                    val endLine = findMethodEnd(lines, i)
                    return Extraction(
                        content = lines.subList(i, endLine).joinToString("\n"),
                        startLine = i + 1,
                        endLine = endLine,
                    )
                }
            }
        }

        return null
    }

    /**
     * Finds the start of a method declaration by walking backwards from a given line.
     * This handles cases where the bytecode line number points to the method body
     * rather than the declaration.
     */
    private fun findMethodDeclarationStart(
        lines: List<String>,
        fromIndex: Int,
        language: SourceLanguage,
    ): Int {
        val methodKeywords =
            when (language) {
                SourceLanguage.KOTLIN -> listOf("fun ", "override fun ", "private fun ", "public fun ", "internal fun ", "protected fun ")
                SourceLanguage.JAVA -> listOf("void ", "public ", "private ", "protected ", "static ", "final ", "abstract ")
                else -> listOf("fun ", "void ", "public ", "private ")
            }

        // Walk backwards to find method declaration
        var current = fromIndex
        while (current > 0) {
            val line = lines[current].trim()

            // Check for method keywords
            for (keyword in methodKeywords) {
                if (line.contains(keyword) && line.contains("(")) {
                    return current
                }
            }

            // Check for annotation (indicates we've gone too far back)
            if (line.startsWith("@") && current < fromIndex) {
                // Include annotations
                return current
            }

            // Don't walk back more than 10 lines
            if (fromIndex - current > 10) break

            current--
        }

        return fromIndex
    }

    /**
     * Finds the end of a method by tracking brace nesting.
     */
    private fun findMethodEnd(
        lines: List<String>,
        startIndex: Int,
    ): Int {
        var braceCount = 0
        var foundStart = false
        var inString = false
        var inChar = false
        var prevChar = ' '

        for (i in startIndex until lines.size) {
            val line = lines[i]
            for (j in line.indices) {
                val char = line[j]

                // Track string/char literals to avoid counting braces inside them
                if (char == '"' && prevChar != '\\' && !inChar) {
                    inString = !inString
                } else if (char == '\'' && prevChar != '\\' && !inString) {
                    inChar = !inChar
                }

                if (!inString && !inChar) {
                    when (char) {
                        '{' -> {
                            braceCount++
                            foundStart = true
                        }
                        '}' -> {
                            braceCount--
                            if (foundStart && braceCount == 0) {
                                return i + 1 // Return 1-based end line (exclusive)
                            }
                        }
                    }
                }
                prevChar = char
            }
        }

        // If we couldn't find a closing brace, return the last line
        return lines.size
    }

    /**
     * Checks if a line containing a method name is actually a method declaration
     * rather than a method call.
     */
    private fun isMethodDeclaration(
        lines: List<String>,
        index: Int,
        methodName: String,
        language: SourceLanguage,
    ): Boolean {
        val line = lines[index].trim()

        // Check for common declaration patterns
        when (language) {
            SourceLanguage.KOTLIN -> {
                // Kotlin: look for "fun" keyword
                if (line.contains("fun ") || line.contains("fun\t")) {
                    return true
                }
                // Check previous lines for annotations/modifiers
                if (index > 0) {
                    val prevLine = lines[index - 1].trim()
                    if (prevLine.startsWith("@") ||
                        prevLine == "override" ||
                        prevLine.endsWith("fun") ||
                        prevLine.contains(" fun ")
                    ) {
                        return true
                    }
                }
            }
            SourceLanguage.JAVA -> {
                // Java: look for return type before method name
                val beforeMethod = line.substringBefore(methodName).trim()
                // Should have a return type (void, Type, etc.) or modifiers
                if (beforeMethod.isNotEmpty() && !beforeMethod.endsWith(".") && !beforeMethod.endsWith("(")) {
                    return true
                }
            }
            else -> {
                return line.contains("{") || (index < lines.size - 1 && lines[index + 1].trim().startsWith("{"))
            }
        }

        return false
    }
}
