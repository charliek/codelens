package codelens.classgraph

import codelens.core.model.ConstantKind
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises [ClassGraphProvider.getCalls] / [CallSiteExtractor] against real
 * compiled bytecode.
 *
 * Hermetic: it scans this test module's own compiled output (the directory
 * entries on the runtime classpath), which contains [CallSiteJavaSample]. No
 * external fixture project or framework dependency is needed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CallSiteExtractorTest {
    private lateinit var provider: ClassGraphProviderImpl

    private val sampleFqn = "codelens.classgraph.fixtures.CallSiteJavaSample"

    @BeforeAll
    fun setup() {
        // Scan only the directory entries on the test classpath — i.e. this
        // module's compiled classes (where the fixture lives) — so the scan is
        // fast and the fixture resolves to a PROJECT class with class bytes.
        val classpathDirs =
            System
                .getProperty("java.class.path")
                .split(File.pathSeparator)
                .map { File(it) }
                .filter { it.isDirectory }

        provider = ClassGraphProviderImpl()
        provider.scan(classpathDirs, classpathDirs.toSet())
    }

    @Test
    fun `getCalls captures a virtual call with its string constant`() {
        val result = provider.getCalls(sampleFqn, "makeCalls")
        val calls = result.methods.single { it.methodName == "makeCalls" }.calls

        val append =
            calls.firstOrNull { it.ownerType == "java.lang.StringBuilder" && it.methodName == "append" }
        assertNotNull(append, "expected a StringBuilder.append call; got $calls")
        assertTrue(
            append.constantArgs.any { it.kind == ConstantKind.STRING && it.value == "alpha" },
            "append should carry the 'alpha' string constant; got ${append.constantArgs}",
        )
    }

    @Test
    fun `getCalls captures an interface call and flags isInterface`() {
        val calls =
            provider
                .getCalls(sampleFqn, "makeCalls")
                .methods
                .single()
                .calls

        val add = calls.firstOrNull { it.methodName == "add" && it.ownerType == "java.util.List" }
        assertNotNull(add, "expected an invokeinterface List.add; got $calls")
        assertTrue(add.isInterface, "List.add should be flagged isInterface")
        assertTrue(add.constantArgs.any { it.kind == ConstantKind.STRING && it.value == "beta" })
    }

    @Test
    fun `getCalls captures constructor calls`() {
        val calls =
            provider
                .getCalls(sampleFqn, "makeCalls")
                .methods
                .single()
                .calls
        assertTrue(
            calls.any { it.ownerType == "java.util.ArrayList" && it.methodName == "<init>" },
            "expected an ArrayList constructor call; got $calls",
        )
    }

    @Test
    fun `getCalls captures class-literal and string constants on one call`() {
        val calls =
            provider
                .getCalls(sampleFqn, "makeCalls")
                .methods
                .single()
                .calls

        val register = calls.firstOrNull { it.methodName == "register" }
        assertNotNull(register, "expected a register call; got $calls")
        assertTrue(
            register.constantArgs.any { it.kind == ConstantKind.CLASS && it.value == "java.lang.String" },
            "register should carry the String.class literal; got ${register.constantArgs}",
        )
        assertTrue(
            register.constantArgs.any { it.kind == ConstantKind.STRING && it.value == "gamma" },
            "register should carry the 'gamma' string; got ${register.constantArgs}",
        )
    }

    @Test
    fun `getCalls records source line numbers when debug info is present`() {
        val calls =
            provider
                .getCalls(sampleFqn, "makeCalls")
                .methods
                .single()
                .calls
        assertTrue(calls.all { it.lineNumber != null }, "every call should have a line number")
    }

    @Test
    fun `whole-class view omits methods that make no calls`() {
        val result = provider.getCalls(sampleFqn)
        val methodNames = result.methods.map { it.methodName }.toSet()
        assertTrue("makeCalls" in methodNames, "makeCalls should appear; got $methodNames")
        assertTrue("noCalls" !in methodNames, "noCalls makes no calls and should be omitted; got $methodNames")
    }

    @Test
    fun `single-method query for an unknown method returns no methods`() {
        val result = provider.getCalls(sampleFqn, "doesNotExist")
        assertEquals(emptyList(), result.methods)
    }

    @Test
    fun `getCalls on an unknown class returns an empty result`() {
        val result = provider.getCalls("does.not.Exist")
        assertEquals("does.not.Exist", result.fqn)
        assertEquals(emptyList(), result.methods)
    }

    private val lambdaFqn = "codelens.classgraph.fixtures.LambdaSample"

    @Test
    fun `getCalls resolves a lambda to its synthetic implementation method`() {
        val calls =
            provider
                .getCalls(lambdaFqn, "makeLambda")
                .methods
                .single()
                .calls

        val lambda = calls.firstOrNull { it.invokeDynamic }
        assertNotNull(lambda, "expected an invokedynamic lambda call site; got $calls")
        assertEquals("java.lang.Runnable", lambda.ownerType, "SAM type should be the functional interface")
        assertEquals("run", lambda.methodName, "methodName should be the functional-interface method")
        assertEquals(lambdaFqn, lambda.implMethodOwner)
        assertEquals(
            "lambda\$makeLambda\$0",
            lambda.implMethodName,
            "a lambda body resolves to a synthetic lambda\$ method; got ${lambda.implMethodName}",
        )
    }

    @Test
    fun `getCalls resolves a method reference to the referenced method`() {
        val calls =
            provider
                .getCalls(lambdaFqn, "methodRef")
                .methods
                .single()
                .calls

        val ref = calls.firstOrNull { it.invokeDynamic }
        assertNotNull(ref, "expected an invokedynamic method-reference call site; got $calls")
        assertEquals("java.util.function.Supplier", ref.ownerType)
        assertEquals("get", ref.methodName)
        assertEquals(lambdaFqn, ref.implMethodOwner)
        assertEquals(
            "provide",
            ref.implMethodName,
            "a method reference resolves directly to the referenced method; got ${ref.implMethodName}",
        )
    }

    @Test
    fun `getCalls skips a string-concatenation invokedynamic`() {
        // `"value-" + suffix` compiles to a StringConcatFactory invokedynamic on
        // Java 9+. It is not a call we model, so the method should expose no call
        // sites at all — and certainly none flagged invokeDynamic.
        val concat = provider.getCalls(lambdaFqn, "concat").methods.single()
        assertTrue(
            concat.calls.none { it.invokeDynamic },
            "string concatenation must not be reported as an invokedynamic call; got ${concat.calls}",
        )
        assertEquals(
            emptyList(),
            concat.calls,
            "a method whose only invokedynamic is string concatenation makes no modeled calls; got ${concat.calls}",
        )
    }

    @Test
    fun `the lambda creation site carries no constant args`() {
        // The constant window passes through an invokedynamic to the call that
        // consumes the lambda, so the creation site itself reports no constants.
        val lambda =
            provider
                .getCalls(lambdaFqn, "makeLambda")
                .methods
                .single()
                .calls
                .single { it.invokeDynamic }
        assertEquals(emptyList(), lambda.constantArgs)
    }
}
