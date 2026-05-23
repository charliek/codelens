package codelens.source.format

import codelens.core.model.*
import codelens.core.model.source.SourceFormat
import codelens.source.model.StubLanguage
import codelens.source.model.VisibilityFilter

/**
 * Generates source stubs from ClassInfo (bytecode metadata).
 * Works for ANY class - no source code required.
 */
class StubGenerator {
    /**
     * Generates a source stub from ClassInfo.
     *
     * @param classInfo The class metadata from bytecode analysis
     * @param language Target language for the stub (JAVA or KOTLIN)
     * @param visibility Filter for member visibility
     * @param format Output format (STUB or SIGNATURES)
     * @return Generated stub source code
     */
    fun generateStub(
        classInfo: ClassInfo,
        language: StubLanguage = StubLanguage.JAVA,
        visibility: VisibilityFilter = VisibilityFilter.ALL,
        format: SourceFormat = SourceFormat.STUB,
    ): String =
        when (language) {
            StubLanguage.JAVA -> generateJavaStub(classInfo, visibility, format)
            StubLanguage.KOTLIN -> generateKotlinStub(classInfo, visibility, format)
        }

    // ========== Java Stub Generation ==========

    private fun generateJavaStub(
        classInfo: ClassInfo,
        visibility: VisibilityFilter,
        format: SourceFormat,
    ): String {
        val sb = StringBuilder()

        // Package declaration
        if (classInfo.name.packageName.isNotEmpty()) {
            sb.appendLine("package ${classInfo.name.packageName};")
            sb.appendLine()
        }

        // Class annotations (filtered)
        for (annotation in classInfo.annotations.filter { !it.type.startsWith("kotlin.") }) {
            sb.appendLine("@${simplifyType(annotation.type)}")
        }

        // Class declaration
        sb.append(javaVisibility(classInfo.visibility))
        if (classInfo.isAbstract && !classInfo.isInterface) sb.append("abstract ")
        if (classInfo.isFinal) sb.append("final ")

        when {
            classInfo.isInterface -> sb.append("interface ")
            classInfo.isEnum -> sb.append("enum ")
            classInfo.isAnnotation -> sb.append("@interface ")
            else -> sb.append("class ")
        }

        sb.append(classInfo.name.simpleName)

        // Extends
        val superclass = classInfo.superclass
        if (superclass != null && superclass != "java.lang.Object") {
            sb.append(" extends ${simplifyType(superclass)}")
        }

        // Implements
        if (classInfo.interfaces.isNotEmpty()) {
            val keyword = if (classInfo.isInterface) " extends " else " implements "
            sb.append(keyword)
            sb.append(classInfo.interfaces.joinToString(", ") { simplifyType(it) })
        }

        sb.appendLine(" {")

        // Fields
        val filteredFields = classInfo.fields.filter { matchesVisibility(it.visibility, visibility) && !it.isSynthetic() }
        for (field in filteredFields) {
            sb.append("    ")
            sb.append(javaVisibility(field.visibility))
            if (field.isStatic) sb.append("static ")
            if (field.isFinal) sb.append("final ")
            sb.append("${simplifyType(field.type)} ${field.name};")
            sb.appendLine()
        }

        if (filteredFields.isNotEmpty() && (classInfo.constructors.isNotEmpty() || classInfo.methods.isNotEmpty())) {
            sb.appendLine()
        }

        // Constructors
        val filteredConstructors =
            classInfo.constructors.filter {
                matchesVisibility(it.visibility, visibility) && !it.isSynthetic
            }
        for (ctor in filteredConstructors) {
            sb.append("    ")
            sb.append(javaVisibility(ctor.visibility))
            sb.append(classInfo.name.simpleName)
            sb.append("(")
            sb.append(ctor.parameters.joinToString(", ") { "${simplifyType(it.type)} ${it.name}" })
            sb.append(")")
            if (format == SourceFormat.SIGNATURES) {
                sb.appendLine(";")
            } else {
                sb.appendLine(" { /* ... */ }")
            }
        }

        if (filteredConstructors.isNotEmpty() && classInfo.methods.isNotEmpty()) {
            sb.appendLine()
        }

        // Methods
        val filteredMethods =
            classInfo.methods.filter {
                matchesVisibility(it.visibility, visibility) && !it.isSynthetic && !it.isBridgeMethod()
            }
        for (method in filteredMethods) {
            sb.append("    ")
            sb.append(javaVisibility(method.visibility))
            if (method.isStatic) sb.append("static ")
            if (method.isAbstract) sb.append("abstract ")
            if (method.isFinal) sb.append("final ")
            sb.append("${simplifyType(method.returnType)} ${method.name}(")
            sb.append(method.parameters.joinToString(", ") { "${simplifyType(it.type)} ${it.name}" })
            sb.append(")")

            when {
                method.isAbstract || classInfo.isInterface -> sb.appendLine(";")
                format == SourceFormat.SIGNATURES -> sb.appendLine(";")
                else -> sb.appendLine(" { /* ... */ }")
            }
        }

        sb.appendLine("}")

        return sb.toString()
    }

    // ========== Kotlin Stub Generation ==========

    private fun generateKotlinStub(
        classInfo: ClassInfo,
        visibility: VisibilityFilter,
        format: SourceFormat,
    ): String {
        val sb = StringBuilder()

        // Package declaration
        if (classInfo.name.packageName.isNotEmpty()) {
            sb.appendLine("package ${classInfo.name.packageName}")
            sb.appendLine()
        }

        // Class annotations (filtered)
        for (annotation in classInfo.annotations.filter { !it.type.startsWith("kotlin.Metadata") }) {
            sb.appendLine("@${simplifyType(annotation.type)}")
        }

        // Class declaration
        sb.append(kotlinVisibility(classInfo.visibility))
        if (classInfo.isAbstract && !classInfo.isInterface) sb.append("abstract ")
        if (classInfo.isFinal && !classInfo.isEnum) {
            // In Kotlin, classes are final by default, so we don't need to add it
        } else if (!classInfo.isFinal && !classInfo.isInterface && !classInfo.isAbstract) {
            sb.append("open ")
        }

        when {
            classInfo.isInterface -> sb.append("interface ")
            classInfo.isEnum -> sb.append("enum class ")
            classInfo.isAnnotation -> sb.append("annotation class ")
            else -> sb.append("class ")
        }

        sb.append(classInfo.name.simpleName)

        // Primary constructor (if there's exactly one non-synthetic constructor)
        val constructors = classInfo.constructors.filter { !it.isSynthetic }
        if (constructors.size == 1 && !classInfo.isInterface && !classInfo.isEnum) {
            val ctor = constructors[0]
            if (ctor.parameters.isNotEmpty()) {
                sb.append("(")
                sb.append(
                    ctor.parameters.joinToString(", ") {
                        "${it.name}: ${kotlinType(it.type)}"
                    },
                )
                sb.append(")")
            }
        }

        // Extends/Implements
        val supers = mutableListOf<String>()
        val kotlinSuperclass = classInfo.superclass
        if (kotlinSuperclass != null && kotlinSuperclass != "java.lang.Object") {
            supers.add("${simplifyType(kotlinSuperclass)}()")
        }
        supers.addAll(classInfo.interfaces.map { simplifyType(it) })

        if (supers.isNotEmpty()) {
            sb.append(" : ")
            sb.append(supers.joinToString(", "))
        }

        sb.appendLine(" {")

        // Companion object for static members
        val staticMethods =
            classInfo.methods.filter {
                it.isStatic && matchesVisibility(it.visibility, visibility) && !it.isSynthetic && !it.isBridgeMethod()
            }
        val staticFields =
            classInfo.fields.filter {
                it.isStatic && matchesVisibility(it.visibility, visibility) && !it.isSynthetic()
            }

        if (staticMethods.isNotEmpty() || staticFields.isNotEmpty()) {
            sb.appendLine("    companion object {")

            for (field in staticFields) {
                sb.append("        ")
                if (field.isFinal) sb.append("const ") else sb.append("@JvmField ")
                sb.append("val ${field.name}: ${kotlinType(field.type)}")
                if (format != SourceFormat.SIGNATURES) {
                    sb.append(" = TODO()")
                }
                sb.appendLine()
            }

            for (method in staticMethods) {
                sb.append("        @JvmStatic ")
                sb.append(kotlinFun(method, visibility, format))
                sb.appendLine()
            }

            sb.appendLine("    }")
            sb.appendLine()
        }

        // Instance fields (properties)
        val instanceFields =
            classInfo.fields.filter {
                !it.isStatic && matchesVisibility(it.visibility, visibility) && !it.isSynthetic()
            }
        for (field in instanceFields) {
            sb.append("    ")
            sb.append(kotlinVisibility(field.visibility))
            if (field.isFinal) sb.append("val ") else sb.append("var ")
            sb.append("${field.name}: ${kotlinType(field.type)}")
            if (format != SourceFormat.SIGNATURES) {
                sb.append(" = TODO()")
            }
            sb.appendLine()
        }

        if (instanceFields.isNotEmpty()) {
            sb.appendLine()
        }

        // Instance methods
        val instanceMethods =
            classInfo.methods.filter {
                !it.isStatic && matchesVisibility(it.visibility, visibility) && !it.isSynthetic && !it.isBridgeMethod()
            }
        for (method in instanceMethods) {
            sb.append("    ")
            sb.append(kotlinFun(method, visibility, format, classInfo.isInterface))
            sb.appendLine()
        }

        sb.appendLine("}")

        return sb.toString()
    }

    private fun kotlinFun(
        method: MethodInfo,
        visibility: VisibilityFilter,
        format: SourceFormat,
        isInterface: Boolean = false,
    ): String {
        val sb = StringBuilder()

        sb.append(kotlinVisibility(method.visibility))
        if (method.isAbstract && !isInterface) sb.append("abstract ")
        if (!method.isFinal && !method.isAbstract && !isInterface) sb.append("open ")

        sb.append("fun ${method.name}(")
        sb.append(
            method.parameters.joinToString(", ") {
                "${it.name}: ${kotlinType(it.type)}"
            },
        )
        sb.append("): ${kotlinType(method.returnType)}")

        when {
            method.isAbstract || isInterface -> {} // No body needed
            format == SourceFormat.SIGNATURES -> {} // No body for signatures
            else -> sb.append(" = TODO()")
        }

        return sb.toString()
    }

    // ========== Helper Methods ==========

    private fun matchesVisibility(
        visibility: Visibility,
        filter: VisibilityFilter,
    ): Boolean =
        when (filter) {
            VisibilityFilter.ALL -> true
            VisibilityFilter.PUBLIC -> visibility == Visibility.PUBLIC
            VisibilityFilter.PUBLIC_PROTECTED ->
                visibility == Visibility.PUBLIC || visibility == Visibility.PROTECTED
        }

    private fun javaVisibility(visibility: Visibility): String =
        when (visibility) {
            Visibility.PUBLIC -> "public "
            Visibility.PROTECTED -> "protected "
            Visibility.PRIVATE -> "private "
            Visibility.PACKAGE_PRIVATE -> ""
        }

    private fun kotlinVisibility(visibility: Visibility): String =
        when (visibility) {
            Visibility.PUBLIC -> "" // public is default in Kotlin
            Visibility.PROTECTED -> "protected "
            Visibility.PRIVATE -> "private "
            Visibility.PACKAGE_PRIVATE -> "internal "
        }

    private fun simplifyType(fqn: String): String {
        // Handle arrays
        if (fqn.endsWith("[]")) {
            return "${simplifyType(fqn.dropLast(2))}[]"
        }

        // Handle generics
        val genericStart = fqn.indexOf('<')
        if (genericStart != -1) {
            val baseName = fqn.substring(0, genericStart)
            val genericPart = fqn.substring(genericStart)
            return "${simplifyType(baseName)}$genericPart"
        }

        // Common Java types that should be simplified
        return when {
            fqn.startsWith("java.lang.") -> fqn.removePrefix("java.lang.")
            fqn.startsWith("java.util.") -> fqn.removePrefix("java.util.")
            else -> fqn.substringAfterLast('.')
        }
    }

    private fun kotlinType(javaType: String): String {
        // Handle arrays
        if (javaType.endsWith("[]")) {
            val elementType = kotlinType(javaType.dropLast(2))
            return "Array<$elementType>"
        }

        // Map Java primitives to Kotlin types
        return when (javaType) {
            "void" -> "Unit"
            "boolean" -> "Boolean"
            "byte" -> "Byte"
            "short" -> "Short"
            "int" -> "Int"
            "long" -> "Long"
            "float" -> "Float"
            "double" -> "Double"
            "char" -> "Char"
            "java.lang.String" -> "String"
            "java.lang.Object" -> "Any"
            "java.lang.Boolean" -> "Boolean"
            "java.lang.Byte" -> "Byte"
            "java.lang.Short" -> "Short"
            "java.lang.Integer" -> "Int"
            "java.lang.Long" -> "Long"
            "java.lang.Float" -> "Float"
            "java.lang.Double" -> "Double"
            "java.lang.Character" -> "Char"
            else -> simplifyType(javaType)
        }
    }

    private fun FieldInfo.isSynthetic(): Boolean {
        // Detect synthetic fields by common naming patterns
        return name.startsWith("this$") ||
            name.contains("$") ||
            name == "serialVersionUID"
    }

    private fun MethodInfo.isBridgeMethod(): Boolean {
        // Bridge methods typically have synthetic-looking patterns
        return name.contains("$") ||
            (isSynthetic && name.startsWith("access$"))
    }
}
