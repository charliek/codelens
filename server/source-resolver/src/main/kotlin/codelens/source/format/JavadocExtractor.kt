package codelens.source.format

import codelens.source.model.VisibilityFilter

/**
 * Extracts Javadoc/KDoc comments from source code.
 * Returns source with only signatures and their doc comments.
 */
class JavadocExtractor {
    /**
     * Extracts signatures with their doc comments from source code.
     *
     * @param source The full source code
     * @param language Source language ("java" or "kotlin")
     * @param visibility Visibility filter
     * @return Source containing only signatures and doc comments
     */
    fun extractWithDocs(
        source: String,
        language: String = "java",
        visibility: VisibilityFilter = VisibilityFilter.ALL,
    ): String {
        val lines = source.lines()
        val result = StringBuilder()

        var inDocComment = false
        var docCommentBuffer = StringBuilder()
        var braceDepth = 0
        var inClass = false
        var skipUntilNextMember = false

        // First, extract the package declaration
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("package ")) {
                result.appendLine(line)
                result.appendLine()
                break
            }
        }

        for (line in lines) {
            val trimmed = line.trim()

            // Track brace depth
            braceDepth += line.count { it == '{' } - line.count { it == '}' }

            // Handle doc comments
            if (trimmed.startsWith("/**")) {
                inDocComment = true
                docCommentBuffer = StringBuilder()
                docCommentBuffer.appendLine(line)
                continue
            }

            if (inDocComment) {
                docCommentBuffer.appendLine(line)
                if (trimmed.contains("*/")) {
                    inDocComment = false
                }
                continue
            }

            // Skip if we're inside a method body
            if (skipUntilNextMember && braceDepth > 1) {
                continue
            }
            skipUntilNextMember = false

            // Handle class declaration
            if (isClassDeclaration(trimmed)) {
                if (matchesVisibilityFilter(trimmed, visibility)) {
                    // Add any pending doc comment
                    if (docCommentBuffer.isNotEmpty()) {
                        result.append(docCommentBuffer)
                        docCommentBuffer.clear()
                    }
                    result.appendLine(line)
                    inClass = true
                }
                continue
            }

            // Handle member declarations (methods, fields, constructors)
            if (inClass && braceDepth >= 1) {
                if (isMemberDeclaration(trimmed)) {
                    if (matchesVisibilityFilter(trimmed, visibility)) {
                        // Add doc comment if present
                        if (docCommentBuffer.isNotEmpty()) {
                            result.append(docCommentBuffer)
                        }

                        // Add the signature (strip body if present)
                        val signature = extractSignature(line, trimmed)
                        result.appendLine(signature)
                        result.appendLine()

                        // Skip the method body
                        if (trimmed.contains("{") && !trimmed.endsWith("}")) {
                            skipUntilNextMember = true
                        }
                    }
                    docCommentBuffer.clear()
                    continue
                }
            }

            // Handle closing brace of class
            if (braceDepth == 0 && inClass) {
                result.appendLine("}")
                inClass = false
            }

            // Clear doc comment buffer if not followed by a declaration
            if (!trimmed.startsWith("@") && !trimmed.isEmpty()) {
                docCommentBuffer.clear()
            }
        }

        return result.toString().trimEnd() + "\n"
    }

    private fun isClassDeclaration(line: String): Boolean {
        val keywords = listOf("class ", "interface ", "enum ", "@interface ", "object ")
        return keywords.any { line.contains(it) } &&
            (
                line.contains("public ") ||
                    line.contains("protected ") ||
                    line.contains("private ") ||
                    !line.contains(" ")
            )
    }

    private fun isMemberDeclaration(line: String): Boolean {
        // Skip empty lines, comments, and annotations
        if (line.isEmpty() || line.startsWith("//") || line.startsWith("@") || line.startsWith("*")) {
            return false
        }

        // Method or constructor declaration (contains parentheses but not just a call)
        if (line.contains("(") &&
            (
                line.contains("public ") ||
                    line.contains("protected ") ||
                    line.contains("private ") ||
                    line.contains("fun ") ||
                    line.contains("void ") ||
                    line.matches(Regex("^\\s*\\w+\\s*\\(.*"))
            )
        ) {
            return true
        }

        // Field declaration (contains type and name, possibly with assignment)
        if ((
                line.contains("public ") ||
                    line.contains("protected ") ||
                    line.contains("private ") ||
                    line.contains("val ") ||
                    line.contains("var ")
            ) &&
            !line.contains("(")
        ) {
            return true
        }

        return false
    }

    private fun matchesVisibilityFilter(
        line: String,
        filter: VisibilityFilter,
    ): Boolean =
        when (filter) {
            VisibilityFilter.ALL -> true
            VisibilityFilter.PUBLIC -> line.contains("public ") || (!line.contains("private ") && !line.contains("protected "))
            VisibilityFilter.PUBLIC_PROTECTED -> !line.contains("private ")
        }

    private fun extractSignature(
        originalLine: String,
        trimmedLine: String,
    ): String {
        // Find where the body starts
        val braceIndex = originalLine.indexOf('{')
        return if (braceIndex != -1) {
            // Has a body - extract just the signature
            val signature = originalLine.substring(0, braceIndex).trimEnd()
            if (trimmedLine.contains("abstract ") || isInterfaceMethod(trimmedLine)) {
                "$signature;"
            } else {
                "$signature { /* ... */ }"
            }
        } else if (trimmedLine.endsWith(";")) {
            // Already just a signature (abstract method or field)
            originalLine
        } else {
            // No brace, no semicolon - add semicolon
            "$originalLine;"
        }
    }

    private fun isInterfaceMethod(line: String): Boolean {
        // Interface methods don't have bodies in Java (before default methods)
        return !line.contains("default ") &&
            !line.contains("static ") &&
            line.contains("(") &&
            !line.contains("{")
    }

    companion object {
        /**
         * Quick check if source contains any doc comments.
         */
        fun hasDocComments(source: String): Boolean = source.contains("/**")
    }
}
