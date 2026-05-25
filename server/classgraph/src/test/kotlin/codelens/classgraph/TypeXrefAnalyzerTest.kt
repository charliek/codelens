package codelens.classgraph

import codelens.core.model.XrefKind
import codelens.core.model.XrefReference
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.assertTrue

/**
 * Exercises [ClassGraphProvider.getReferencesToType] / [TypeXrefAnalyzer]
 * against real compiled bytecode.
 *
 * Hermetic: scans this test module's compiled output, which contains
 * [XrefSignatureSample] (signature-level refs) and [CallSiteJavaSample]
 * (bytecode-level refs). Assertions look for specific references rather than
 * exact totals, since other compiled test classes also reference common JDK
 * types.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TypeXrefAnalyzerTest {
    private lateinit var provider: ClassGraphProviderImpl

    private val signatureSample = "codelens.classgraph.fixtures.XrefSignatureSample"
    private val callSample = "codelens.classgraph.fixtures.CallSiteJavaSample"

    @BeforeAll
    fun setup() {
        val classpathDirs =
            System
                .getProperty("java.class.path")
                .split(File.pathSeparator)
                .map { File(it) }
                .filter { it.isDirectory }
        provider = ClassGraphProviderImpl()
        provider.scan(classpathDirs, classpathDirs.toSet())
    }

    private fun refsFrom(
        refs: List<XrefReference>,
        from: String,
    ) = refs.filter { it.fromFqn == from }

    @Test
    fun `xref finds EXTENDS and IMPLEMENTS via signatures`() {
        val ext = refsFrom(provider.getReferencesToType("java.util.ArrayList"), signatureSample)
        assertTrue(ext.any { it.kind == XrefKind.EXTENDS }, "expected EXTENDS from $signatureSample; got $ext")

        val impl = refsFrom(provider.getReferencesToType("java.io.Serializable"), signatureSample)
        assertTrue(impl.any { it.kind == XrefKind.IMPLEMENTS }, "expected IMPLEMENTS from $signatureSample; got $impl")
    }

    @Test
    fun `xref finds FIELD, PARAM and RETURN via signatures`() {
        val field = refsFrom(provider.getReferencesToType("java.util.Map"), signatureSample)
        assertTrue(field.any { it.kind == XrefKind.FIELD && it.member == "cache" }, "expected FIELD cache; got $field")

        val param = refsFrom(provider.getReferencesToType("java.util.Collection"), signatureSample)
        assertTrue(param.any { it.kind == XrefKind.PARAM && it.member == "getItems" }, "expected PARAM getItems; got $param")

        val ret = refsFrom(provider.getReferencesToType("java.util.List"), signatureSample)
        assertTrue(ret.any { it.kind == XrefKind.RETURN && it.member == "getItems" }, "expected RETURN getItems; got $ret")
    }

    @Test
    fun `xref finds INSTANTIATION and CALL_RECEIVER via bytecode`() {
        val inst = refsFrom(provider.getReferencesToType("java.util.ArrayList"), callSample)
        assertTrue(inst.any { it.kind == XrefKind.INSTANTIATION }, "expected INSTANTIATION from $callSample; got $inst")

        val recv = refsFrom(provider.getReferencesToType("java.util.List"), callSample)
        assertTrue(
            recv.any { it.kind == XrefKind.CALL_RECEIVER && it.detail == "add" },
            "expected CALL_RECEIVER List.add from $callSample; got $recv",
        )
    }

    @Test
    fun `scopeImplementing intersects referencing classes`() {
        // Only XrefSignatureSample implements the marker; CallSiteJavaSample does not.
        // (The scope type must be a scanned class — here a project interface — for
        // getImplementations to resolve it; in real runs library interfaces are scanned too.)
        val scoped =
            provider.getReferencesToType(
                "java.util.List",
                scopeImplementing = "codelens.classgraph.fixtures.XrefScopeMarker",
            )
        assertTrue(scoped.isNotEmpty(), "expected at least the RETURN ref from the marker-implementing class")
        assertTrue(
            scoped.all { it.fromFqn == signatureSample },
            "scopeImplementing must exclude classes that don't implement the scope type; got ${scoped.map { it.fromFqn }.distinct()}",
        )
    }

    @Test
    fun `a type does not cross-reference itself`() {
        val refs = provider.getReferencesToType(callSample)
        assertTrue(refs.none { it.fromFqn == callSample }, "self-references should be excluded")
    }

    @Test
    fun `a type used only inside a lambda body is attributed to the enclosing class`() {
        // LambdaSample uses java.util.StringJoiner solely inside its lambda body,
        // which the compiler emits as a synthetic lambda$ method. getCalls scans
        // those, so the reference is attributed to the enclosing class.
        val lambdaSample = "codelens.classgraph.fixtures.LambdaSample"
        val refs = refsFrom(provider.getReferencesToType("java.util.StringJoiner"), lambdaSample)
        assertTrue(
            refs.any { it.kind == XrefKind.INSTANTIATION && it.member?.startsWith("lambda\$") == true },
            "expected an INSTANTIATION attributed to a lambda\$ method of $lambdaSample; got $refs",
        )
        assertTrue(
            refs.any { it.kind == XrefKind.CALL_RECEIVER && it.detail == "add" },
            "expected a CALL_RECEIVER (add) from inside the lambda body; got $refs",
        )
    }

    @Test
    fun `invokedynamic lambda sites do not register the SAM type as a call receiver`() {
        // The lambda in LambdaSample.makeLambda implements java.lang.Runnable, but
        // creating it must not be recorded as a CALL_RECEIVER on Runnable.
        val refs = refsFrom(provider.getReferencesToType("java.lang.Runnable"), "codelens.classgraph.fixtures.LambdaSample")
        assertTrue(
            refs.none { it.kind == XrefKind.CALL_RECEIVER },
            "an invokedynamic lambda creation must not be a CALL_RECEIVER on the SAM type; got $refs",
        )
    }
}
