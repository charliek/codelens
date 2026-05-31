package codelens.classgraph

import codelens.core.model.AnnotationValue
import codelens.core.model.AnnotationValueKind
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Exercises the typed annotation-value conversion (#41) end-to-end through the
 * real provider scan (ClassGraph `enableAllInfo()` + `convertAnnotation` /
 * `toAnnotationValue`) against a compiled Java fixture. This covers the actual
 * ClassGraph wrapper types — `AnnotationEnumValue`, `AnnotationClassRef`, nested
 * `AnnotationInfo`, and primitive/object arrays — rather than hand-rolled inputs
 * to a private method (which the old reflection-based test did, against branches
 * that never fire in production).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnnotationValueConversionTest {
    private lateinit var provider: ClassGraphProviderImpl

    private val fixturePkg = "codelens.classgraph.fixtures"
    private val richType = "$fixturePkg.RichAnnotation"
    private val annotatedFqn = "$fixturePkg.AnnotationValueSample\$Annotated"
    private val emptyArraysFqn = "$fixturePkg.AnnotationValueSample\$EmptyArrays"

    @BeforeAll
    fun setup() {
        // Scan this module's compiled classes (the directory classpath entries),
        // where the fixture lives, so it resolves to a PROJECT class with
        // annotation info — exactly the production scan configuration.
        val classpathDirs =
            System
                .getProperty("java.class.path")
                .split(File.pathSeparator)
                .map { File(it) }
                .filter { it.isDirectory }

        provider = ClassGraphProviderImpl()
        provider.scan(classpathDirs, classpathDirs.toSet())
    }

    /** The `@RichAnnotation` attribute map on a fixture class. */
    private fun richParams(classFqn: String): Map<String, AnnotationValue> {
        val classInfo = assertNotNull(provider.getClass(classFqn), "fixture $classFqn was not scanned")
        val rich = classInfo.annotations.single { it.type == richType }
        return rich.parameters
    }

    @Test
    fun `scalar string attribute is a STRING value`() {
        assertEquals(
            AnnotationValue(AnnotationValueKind.STRING, value = "alpha"),
            richParams(annotatedFqn)["name"],
        )
    }

    @Test
    fun `boolean attribute is a BOOLEAN value`() {
        assertEquals(
            AnnotationValue(AnnotationValueKind.BOOLEAN, value = "true"),
            richParams(annotatedFqn)["flag"],
        )
    }

    @Test
    fun `string array is an ARRAY of STRING items`() {
        assertEquals(
            AnnotationValue(
                AnnotationValueKind.ARRAY,
                items =
                    listOf(
                        AnnotationValue(AnnotationValueKind.STRING, value = "/a"),
                        AnnotationValue(AnnotationValueKind.STRING, value = "/b"),
                    ),
            ),
            richParams(annotatedFqn)["paths"],
        )
    }

    @Test
    fun `explicitly empty string array is an ARRAY with no items`() {
        assertEquals(
            AnnotationValue(AnnotationValueKind.ARRAY, items = emptyList()),
            richParams(emptyArraysFqn)["paths"],
        )
    }

    @Test
    fun `primitive int array is an ARRAY of INT items`() {
        assertEquals(
            AnnotationValue(
                AnnotationValueKind.ARRAY,
                items =
                    listOf(
                        AnnotationValue(AnnotationValueKind.INT, value = "1"),
                        AnnotationValue(AnnotationValueKind.INT, value = "2"),
                        AnnotationValue(AnnotationValueKind.INT, value = "3"),
                    ),
            ),
            richParams(annotatedFqn)["codes"],
        )
    }

    @Test
    fun `enum array is an ARRAY of ENUM items carrying type and constant`() {
        assertEquals(
            AnnotationValue(
                AnnotationValueKind.ARRAY,
                items =
                    listOf(
                        AnnotationValue(AnnotationValueKind.ENUM, value = "RED", enumType = "$fixturePkg.AnnotationColor"),
                        AnnotationValue(AnnotationValueKind.ENUM, value = "BLUE", enumType = "$fixturePkg.AnnotationColor"),
                    ),
            ),
            richParams(annotatedFqn)["colors"],
        )
    }

    @Test
    fun `class literal is a CLASS value with the dotted FQN and no dot-class suffix`() {
        assertEquals(
            AnnotationValue(AnnotationValueKind.CLASS, value = "java.lang.String"),
            richParams(annotatedFqn)["target"],
        )
    }

    @Test
    fun `nested annotation is an ANNOTATION value with a populated annotation`() {
        val nested = richParams(annotatedFqn)["nested"]
        assertEquals(AnnotationValueKind.ANNOTATION, nested?.kind)
        val ann = assertNotNull(nested?.annotation, "nested annotation should be populated")
        assertEquals("$fixturePkg.NestedAnnotation", ann.type)
        assertEquals(AnnotationValue(AnnotationValueKind.STRING, value = "inner"), ann.parameters["label"])
        assertEquals(AnnotationValue(AnnotationValueKind.INT, value = "7"), ann.parameters["order"])
    }
}
