package codelens.source.format

import codelens.core.model.*
import codelens.core.model.source.SourceFormat
import codelens.source.model.StubLanguage
import codelens.source.model.VisibilityFilter
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StubGeneratorTest {

    private val stubGenerator = StubGenerator()

    private fun createTestClassInfo(
        packageName: String = "com.example",
        simpleName: String = "TestClass",
        isInterface: Boolean = false,
        isAbstract: Boolean = false,
        superclass: String? = null,
        interfaces: List<String> = emptyList(),
        methods: List<MethodInfo> = emptyList(),
        fields: List<FieldInfo> = emptyList()
    ): ClassInfo {
        return ClassInfo(
            name = ClassName(
                fqn = "$packageName.$simpleName",
                simpleName = simpleName,
                packageName = packageName
            ),
            source = ClassSource.LIBRARY,
            visibility = Visibility.PUBLIC,
            isInterface = isInterface,
            isAbstract = isAbstract,
            superclass = superclass,
            interfaces = interfaces,
            methods = methods,
            fields = fields
        )
    }

    @Test
    fun `generates Java package declaration`() {
        val classInfo = createTestClassInfo()
        val stub = stubGenerator.generateStub(classInfo, StubLanguage.JAVA)

        assertContains(stub, "package com.example;")
    }

    @Test
    fun `generates Kotlin package declaration`() {
        val classInfo = createTestClassInfo()
        val stub = stubGenerator.generateStub(classInfo, StubLanguage.KOTLIN)

        assertContains(stub, "package com.example")
        assertFalse(stub.contains("package com.example;"))
    }

    @Test
    fun `generates Java class with extends`() {
        val classInfo = createTestClassInfo(
            superclass = "com.example.BaseClass"
        )
        val stub = stubGenerator.generateStub(classInfo, StubLanguage.JAVA)

        assertContains(stub, "extends BaseClass")
    }

    @Test
    fun `generates Java class with implements`() {
        val classInfo = createTestClassInfo(
            interfaces = listOf("java.io.Serializable", "java.lang.Comparable")
        )
        val stub = stubGenerator.generateStub(classInfo, StubLanguage.JAVA)

        assertContains(stub, "implements Serializable, Comparable")
    }

    @Test
    fun `generates Java interface`() {
        val classInfo = createTestClassInfo(isInterface = true)
        val stub = stubGenerator.generateStub(classInfo, StubLanguage.JAVA)

        assertContains(stub, "public interface TestClass")
    }

    @Test
    fun `generates Kotlin interface`() {
        val classInfo = createTestClassInfo(isInterface = true)
        val stub = stubGenerator.generateStub(classInfo, StubLanguage.KOTLIN)

        assertContains(stub, "interface TestClass")
    }

    @Test
    fun `generates Java method with placeholder body`() {
        val classInfo = createTestClassInfo(
            methods = listOf(
                MethodInfo(
                    name = "doSomething",
                    visibility = Visibility.PUBLIC,
                    returnType = "void",
                    parameters = listOf(
                        ParameterInfo("name", "java.lang.String")
                    )
                )
            )
        )
        val stub = stubGenerator.generateStub(classInfo, StubLanguage.JAVA, format = SourceFormat.STUB)

        assertContains(stub, "public void doSomething(String name) { /* ... */ }")
    }

    @Test
    fun `generates Kotlin method with TODO body`() {
        val classInfo = createTestClassInfo(
            methods = listOf(
                MethodInfo(
                    name = "doSomething",
                    visibility = Visibility.PUBLIC,
                    returnType = "java.lang.String",
                    parameters = listOf(
                        ParameterInfo("count", "int")
                    )
                )
            )
        )
        val stub = stubGenerator.generateStub(classInfo, StubLanguage.KOTLIN, format = SourceFormat.STUB)

        assertContains(stub, "fun doSomething(count: Int): String = TODO()")
    }

    @Test
    fun `generates signatures only without bodies`() {
        val classInfo = createTestClassInfo(
            methods = listOf(
                MethodInfo(
                    name = "getValue",
                    visibility = Visibility.PUBLIC,
                    returnType = "int"
                )
            )
        )
        val stub = stubGenerator.generateStub(classInfo, StubLanguage.JAVA, format = SourceFormat.SIGNATURES)

        assertContains(stub, "public int getValue();")
        assertFalse(stub.contains("{ /* ... */ }"))
    }

    @Test
    fun `filters by visibility - public only`() {
        val classInfo = createTestClassInfo(
            methods = listOf(
                MethodInfo(
                    name = "publicMethod",
                    visibility = Visibility.PUBLIC,
                    returnType = "void"
                ),
                MethodInfo(
                    name = "privateMethod",
                    visibility = Visibility.PRIVATE,
                    returnType = "void"
                )
            )
        )
        val stub = stubGenerator.generateStub(
            classInfo,
            StubLanguage.JAVA,
            visibility = VisibilityFilter.PUBLIC
        )

        assertContains(stub, "publicMethod")
        assertFalse(stub.contains("privateMethod"))
    }

    @Test
    fun `converts Java primitives to Kotlin types`() {
        val classInfo = createTestClassInfo(
            methods = listOf(
                MethodInfo(
                    name = "process",
                    visibility = Visibility.PUBLIC,
                    returnType = "boolean",
                    parameters = listOf(
                        ParameterInfo("value", "int"),
                        ParameterInfo("name", "java.lang.String")
                    )
                )
            )
        )
        val stub = stubGenerator.generateStub(classInfo, StubLanguage.KOTLIN)

        assertContains(stub, "value: Int")
        assertContains(stub, "name: String")
        assertContains(stub, "Boolean")
    }

    @Test
    fun `generates abstract methods correctly`() {
        val classInfo = createTestClassInfo(
            isAbstract = true,
            methods = listOf(
                MethodInfo(
                    name = "abstractMethod",
                    visibility = Visibility.PUBLIC,
                    returnType = "void",
                    isAbstract = true
                )
            )
        )
        val stub = stubGenerator.generateStub(classInfo, StubLanguage.JAVA)

        assertContains(stub, "public abstract class TestClass")
        assertContains(stub, "public abstract void abstractMethod();")
    }

    @Test
    fun `generates static methods in Kotlin companion object`() {
        val classInfo = createTestClassInfo(
            methods = listOf(
                MethodInfo(
                    name = "staticMethod",
                    visibility = Visibility.PUBLIC,
                    returnType = "void",
                    isStatic = true
                )
            )
        )
        val stub = stubGenerator.generateStub(classInfo, StubLanguage.KOTLIN)

        assertContains(stub, "companion object")
        assertContains(stub, "@JvmStatic")
        assertContains(stub, "staticMethod")
    }

    @Test
    fun `generates fields correctly`() {
        val classInfo = createTestClassInfo(
            fields = listOf(
                FieldInfo(
                    name = "value",
                    visibility = Visibility.PUBLIC,
                    type = "int",
                    isFinal = true
                )
            )
        )
        val javaStub = stubGenerator.generateStub(classInfo, StubLanguage.JAVA)
        val kotlinStub = stubGenerator.generateStub(classInfo, StubLanguage.KOTLIN)

        assertContains(javaStub, "public final int value;")
        assertContains(kotlinStub, "val value: Int")
    }
}
