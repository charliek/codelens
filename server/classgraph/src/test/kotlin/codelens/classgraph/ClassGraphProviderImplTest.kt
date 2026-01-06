package codelens.classgraph

import codelens.core.model.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for ClassGraphProviderImpl.
 */
class ClassGraphProviderImplTest {

    private lateinit var provider: ClassGraphProviderImpl
    private lateinit var classesMap: ConcurrentHashMap<String, ClassInfo>

    @BeforeEach
    fun setup() {
        provider = ClassGraphProviderImpl()
        // Access private classes field via reflection for testing
        val classesField = ClassGraphProviderImpl::class.java.getDeclaredField("classes")
        classesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        classesMap = classesField.get(provider) as ConcurrentHashMap<String, ClassInfo>
    }

    @Test
    fun `getImplementations should not return duplicates`() {
        // Setup: Create an interface and classes that implement it
        val interfaceFqn = "com.example.MyInterface"
        val implClass1Fqn = "com.example.ImplClass1"
        val implClass2Fqn = "com.example.ImplClass2"

        // Add the interface
        classesMap[interfaceFqn] = createClassInfo(
            fqn = interfaceFqn,
            simpleName = "MyInterface",
            packageName = "com.example",
            isInterface = true
        )

        // Add two implementing classes
        classesMap[implClass1Fqn] = createClassInfo(
            fqn = implClass1Fqn,
            simpleName = "ImplClass1",
            packageName = "com.example",
            interfaces = listOf(interfaceFqn)
        )

        classesMap[implClass2Fqn] = createClassInfo(
            fqn = implClass2Fqn,
            simpleName = "ImplClass2",
            packageName = "com.example",
            interfaces = listOf(interfaceFqn)
        )

        // Execute
        val (directImpls, indirectImpls) = provider.getImplementations(interfaceFqn, false)

        // Verify: Should have exactly 2 direct implementations, no duplicates
        assertEquals(2, directImpls.size, "Should have exactly 2 direct implementations")
        assertEquals(0, indirectImpls.size, "Should have no indirect implementations")

        // Verify all FQNs are unique
        val allFqns = directImpls.map { it.fqn }
        assertEquals(allFqns.size, allFqns.distinct().size, "Direct implementations should not contain duplicates")
    }

    @Test
    fun `getImplementations should return unique entries even with complex hierarchy`() {
        // Setup: Interface -> AbstractClass -> ConcreteClass
        // This hierarchy could potentially cause duplicates if not handled correctly
        val interfaceFqn = "com.example.Handler"
        val abstractFqn = "com.example.AbstractHandler"
        val concreteFqn = "com.example.ConcreteHandler"

        // Add the interface
        classesMap[interfaceFqn] = createClassInfo(
            fqn = interfaceFqn,
            simpleName = "Handler",
            packageName = "com.example",
            isInterface = true
        )

        // Add abstract class that implements the interface
        classesMap[abstractFqn] = createClassInfo(
            fqn = abstractFqn,
            simpleName = "AbstractHandler",
            packageName = "com.example",
            isAbstract = true,
            interfaces = listOf(interfaceFqn)
        )

        // Add concrete class that extends abstract class
        classesMap[concreteFqn] = createClassInfo(
            fqn = concreteFqn,
            simpleName = "ConcreteHandler",
            packageName = "com.example",
            superclass = abstractFqn,
            // Note: May also list the interface transitively
            interfaces = listOf(interfaceFqn)
        )

        // Execute
        val (directImpls, indirectImpls) = provider.getImplementations(interfaceFqn, false)

        // Verify: Both should be listed but not duplicated
        val allImpls = directImpls + indirectImpls
        val allFqns = allImpls.map { it.fqn }
        assertEquals(allFqns.size, allFqns.distinct().size,
            "Combined implementations should not contain duplicates. Found: $allFqns")
    }

    @Test
    fun `getImplementations with multiple interfaces should not cause duplicates`() {
        // Setup: Class implements multiple interfaces
        val interface1Fqn = "com.example.Interface1"
        val interface2Fqn = "com.example.Interface2"
        val implClassFqn = "com.example.MultiImpl"

        // Add interfaces
        classesMap[interface1Fqn] = createClassInfo(
            fqn = interface1Fqn,
            simpleName = "Interface1",
            packageName = "com.example",
            isInterface = true
        )
        classesMap[interface2Fqn] = createClassInfo(
            fqn = interface2Fqn,
            simpleName = "Interface2",
            packageName = "com.example",
            isInterface = true
        )

        // Add implementing class
        classesMap[implClassFqn] = createClassInfo(
            fqn = implClassFqn,
            simpleName = "MultiImpl",
            packageName = "com.example",
            interfaces = listOf(interface1Fqn, interface2Fqn)
        )

        // Execute: Query implementations for first interface
        val (directImpls1, _) = provider.getImplementations(interface1Fqn, false)

        // Verify: Should have exactly 1 implementation
        assertEquals(1, directImpls1.size, "Should have exactly 1 direct implementation")
        assertEquals(implClassFqn, directImpls1[0].fqn)

        // Execute: Query implementations for second interface
        val (directImpls2, _) = provider.getImplementations(interface2Fqn, false)

        // Verify: Should also have exactly 1 implementation
        assertEquals(1, directImpls2.size, "Should have exactly 1 direct implementation for second interface")
        assertEquals(implClassFqn, directImpls2[0].fqn)
    }

    @Test
    fun `getImplementations direct vs indirect separation is correct`() {
        // Setup: Interface -> DirectImpl and Interface -> AbstractBase -> IndirectImpl
        val interfaceFqn = "com.example.Service"
        val directImplFqn = "com.example.DirectService"
        val abstractBaseFqn = "com.example.AbstractService"
        val indirectImplFqn = "com.example.IndirectService"

        classesMap[interfaceFqn] = createClassInfo(
            fqn = interfaceFqn,
            simpleName = "Service",
            packageName = "com.example",
            isInterface = true
        )

        classesMap[directImplFqn] = createClassInfo(
            fqn = directImplFqn,
            simpleName = "DirectService",
            packageName = "com.example",
            interfaces = listOf(interfaceFqn)
        )

        classesMap[abstractBaseFqn] = createClassInfo(
            fqn = abstractBaseFqn,
            simpleName = "AbstractService",
            packageName = "com.example",
            isAbstract = true,
            interfaces = listOf(interfaceFqn)
        )

        classesMap[indirectImplFqn] = createClassInfo(
            fqn = indirectImplFqn,
            simpleName = "IndirectService",
            packageName = "com.example",
            superclass = abstractBaseFqn
            // Note: Does NOT directly list interface - it's inherited
        )

        // Execute
        val (directImpls, indirectImpls) = provider.getImplementations(interfaceFqn, false)

        // Verify direct implementations
        val directFqns = directImpls.map { it.fqn }
        assertTrue(directFqns.contains(directImplFqn), "Should include DirectService in direct impls")
        assertTrue(directFqns.contains(abstractBaseFqn), "Should include AbstractService in direct impls")

        // Verify indirect implementation
        val indirectFqns = indirectImpls.map { it.fqn }
        assertTrue(indirectFqns.contains(indirectImplFqn), "Should include IndirectService in indirect impls")

        // Verify no duplicates across both lists
        val allFqns = directFqns + indirectFqns
        assertEquals(allFqns.size, allFqns.distinct().size, "Should have no duplicates across direct and indirect")
    }

    // ============== Hierarchy Tests ==============

    @Test
    fun `getHierarchy should include java_lang_Object in parent chain`() {
        // Setup: Create a simple class that extends Object
        val classFqn = "com.example.SimpleClass"

        classesMap[classFqn] = createClassInfo(
            fqn = classFqn,
            simpleName = "SimpleClass",
            packageName = "com.example",
            superclass = "java.lang.Object"
        )

        // Execute
        val hierarchy = provider.getHierarchy(classFqn)

        // Verify: Parent should be java.lang.Object
        assertNotNull(hierarchy, "Hierarchy should not be null")
        assertNotNull(hierarchy.parent, "Parent should not be null")
        assertEquals("java.lang.Object", hierarchy.parent?.classFqn)
        assertEquals("Object", hierarchy.parent?.simpleName)
        assertEquals(ClassSource.JDK, hierarchy.parent?.source)
        assertNull(hierarchy.parent?.parent, "Object's parent should be null")
    }

    @Test
    fun `getHierarchy should show complete parent chain for extended classes`() {
        // Setup: Create a class hierarchy: GrandChild -> Parent -> java.lang.Object
        val parentFqn = "com.example.Parent"
        val childFqn = "com.example.Child"

        classesMap[parentFqn] = createClassInfo(
            fqn = parentFqn,
            simpleName = "Parent",
            packageName = "com.example",
            superclass = "java.lang.Object"
        )

        classesMap[childFqn] = createClassInfo(
            fqn = childFqn,
            simpleName = "Child",
            packageName = "com.example",
            superclass = parentFqn
        )

        // Execute
        val hierarchy = provider.getHierarchy(childFqn)

        // Verify: Child -> Parent -> Object
        assertNotNull(hierarchy, "Hierarchy should not be null")
        assertEquals(childFqn, hierarchy.classFqn)

        // Parent level
        assertNotNull(hierarchy.parent, "Parent should not be null")
        assertEquals(parentFqn, hierarchy.parent?.classFqn)

        // Grandparent level (Object)
        assertNotNull(hierarchy.parent?.parent, "Object should be in chain")
        assertEquals("java.lang.Object", hierarchy.parent?.parent?.classFqn)
        assertEquals(ClassSource.JDK, hierarchy.parent?.parent?.source)
        assertNull(hierarchy.parent?.parent?.parent, "Object's parent should be null")
    }

    @Test
    fun `getHierarchy should handle interfaces correctly`() {
        // Setup: Create an interface (no superclass)
        val interfaceFqn = "com.example.MyInterface"

        classesMap[interfaceFqn] = createClassInfo(
            fqn = interfaceFqn,
            simpleName = "MyInterface",
            packageName = "com.example",
            isInterface = true
        )

        // Execute
        val hierarchy = provider.getHierarchy(interfaceFqn)

        // Verify: Interface should have no parent (superclass is null for interfaces)
        assertNotNull(hierarchy, "Hierarchy should not be null")
        assertTrue(hierarchy.isInterface, "Should be marked as interface")
        assertNull(hierarchy.parent, "Interface should have no parent")
    }

    /**
     * Helper function to create ClassInfo for testing.
     */
    private fun createClassInfo(
        fqn: String,
        simpleName: String,
        packageName: String,
        source: ClassSource = ClassSource.PROJECT,
        isInterface: Boolean = false,
        isAbstract: Boolean = false,
        superclass: String? = "java.lang.Object",
        interfaces: List<String> = emptyList()
    ): ClassInfo {
        return ClassInfo(
            name = ClassName(
                fqn = fqn,
                simpleName = simpleName,
                packageName = packageName
            ),
            source = source,
            visibility = Visibility.PUBLIC,
            isInterface = isInterface,
            isAbstract = isAbstract,
            superclass = if (isInterface) null else superclass,
            interfaces = interfaces
        )
    }
}
