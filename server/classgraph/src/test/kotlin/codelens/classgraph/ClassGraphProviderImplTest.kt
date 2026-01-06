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

    // ============== Dependency Tests ==============

    @Test
    fun `getDependencies should find outgoing superclass dependency`() {
        val parentFqn = "com.example.ParentClass"
        val childFqn = "com.example.ChildClass"

        classesMap[parentFqn] = createClassInfo(
            fqn = parentFqn,
            simpleName = "ParentClass",
            packageName = "com.example"
        )

        classesMap[childFqn] = createClassInfo(
            fqn = childFqn,
            simpleName = "ChildClass",
            packageName = "com.example",
            superclass = parentFqn
        )

        val (outgoing, _) = provider.getDependencies(childFqn, false)

        assertEquals(1, outgoing.size, "Should have 1 outgoing dependency")
        assertEquals(parentFqn, outgoing[0].classFqn)
        assertEquals(DependencyType.EXTENDS, outgoing[0].dependencyType)
    }

    @Test
    fun `getDependencies should find outgoing interface dependencies`() {
        val interface1Fqn = "com.example.Interface1"
        val interface2Fqn = "com.example.Interface2"
        val implFqn = "com.example.ImplClass"

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
        classesMap[implFqn] = createClassInfo(
            fqn = implFqn,
            simpleName = "ImplClass",
            packageName = "com.example",
            interfaces = listOf(interface1Fqn, interface2Fqn)
        )

        val (outgoing, _) = provider.getDependencies(implFqn, false)

        val implementsDeps = outgoing.filter { it.dependencyType == DependencyType.IMPLEMENTS }
        assertEquals(2, implementsDeps.size, "Should have 2 IMPLEMENTS dependencies")
        assertTrue(implementsDeps.any { it.classFqn == interface1Fqn })
        assertTrue(implementsDeps.any { it.classFqn == interface2Fqn })
    }

    @Test
    fun `getDependencies should find outgoing field type dependencies`() {
        val serviceFqn = "com.example.ServiceA"
        val consumerFqn = "com.example.Consumer"

        classesMap[serviceFqn] = createClassInfo(
            fqn = serviceFqn,
            simpleName = "ServiceA",
            packageName = "com.example"
        )
        classesMap[consumerFqn] = createClassInfo(
            fqn = consumerFqn,
            simpleName = "Consumer",
            packageName = "com.example",
            fields = listOf(
                FieldInfo(name = "service", type = serviceFqn, visibility = Visibility.PRIVATE)
            )
        )

        val (outgoing, _) = provider.getDependencies(consumerFqn, false)

        val fieldDeps = outgoing.filter { it.dependencyType == DependencyType.FIELD_TYPE }
        assertEquals(1, fieldDeps.size, "Should have 1 FIELD_TYPE dependency")
        assertEquals(serviceFqn, fieldDeps[0].classFqn)
        assertEquals("service", fieldDeps[0].location)
    }

    @Test
    fun `getDependencies should find outgoing method return type dependencies`() {
        val returnTypeFqn = "com.example.Result"
        val serviceFqn = "com.example.MyService"

        classesMap[returnTypeFqn] = createClassInfo(
            fqn = returnTypeFqn,
            simpleName = "Result",
            packageName = "com.example"
        )
        classesMap[serviceFqn] = createClassInfo(
            fqn = serviceFqn,
            simpleName = "MyService",
            packageName = "com.example",
            methods = listOf(
                MethodInfo(
                    name = "getResult",
                    visibility = Visibility.PUBLIC,
                    returnType = returnTypeFqn,
                    parameters = emptyList()
                )
            )
        )

        val (outgoing, _) = provider.getDependencies(serviceFqn, false)

        val returnTypeDeps = outgoing.filter { it.dependencyType == DependencyType.METHOD_RETURN_TYPE }
        assertEquals(1, returnTypeDeps.size, "Should have 1 METHOD_RETURN_TYPE dependency")
        assertEquals(returnTypeFqn, returnTypeDeps[0].classFqn)
        assertEquals("getResult()", returnTypeDeps[0].location)
    }

    @Test
    fun `getDependencies should find outgoing method parameter dependencies`() {
        val requestFqn = "com.example.Request"
        val handlerFqn = "com.example.Handler"

        classesMap[requestFqn] = createClassInfo(
            fqn = requestFqn,
            simpleName = "Request",
            packageName = "com.example"
        )
        classesMap[handlerFqn] = createClassInfo(
            fqn = handlerFqn,
            simpleName = "Handler",
            packageName = "com.example",
            methods = listOf(
                MethodInfo(
                    name = "handle",
                    visibility = Visibility.PUBLIC,
                    returnType = "void",
                    parameters = listOf(
                        ParameterInfo(name = "request", type = requestFqn)
                    )
                )
            )
        )

        val (outgoing, _) = provider.getDependencies(handlerFqn, false)

        val paramDeps = outgoing.filter { it.dependencyType == DependencyType.METHOD_PARAMETER }
        assertEquals(1, paramDeps.size, "Should have 1 METHOD_PARAMETER dependency")
        assertEquals(requestFqn, paramDeps[0].classFqn)
        assertEquals("handle()", paramDeps[0].location)
    }

    @Test
    fun `getDependencies should find incoming dependencies from classes that extend target`() {
        val baseFqn = "com.example.BaseClass"
        val childFqn = "com.example.ChildClass"

        classesMap[baseFqn] = createClassInfo(
            fqn = baseFqn,
            simpleName = "BaseClass",
            packageName = "com.example"
        )
        classesMap[childFqn] = createClassInfo(
            fqn = childFqn,
            simpleName = "ChildClass",
            packageName = "com.example",
            superclass = baseFqn
        )

        val (_, incoming) = provider.getDependencies(baseFqn, false)

        assertEquals(1, incoming.size, "Should have 1 incoming dependency")
        assertEquals(childFqn, incoming[0].classFqn)
        assertEquals(DependencyType.EXTENDS, incoming[0].dependencyType)
    }

    @Test
    fun `getDependencies should find incoming dependencies from classes that use target as field`() {
        val targetFqn = "com.example.TargetService"
        val userFqn = "com.example.ServiceUser"

        classesMap[targetFqn] = createClassInfo(
            fqn = targetFqn,
            simpleName = "TargetService",
            packageName = "com.example"
        )
        classesMap[userFqn] = createClassInfo(
            fqn = userFqn,
            simpleName = "ServiceUser",
            packageName = "com.example",
            fields = listOf(
                FieldInfo(name = "target", type = targetFqn, visibility = Visibility.PRIVATE)
            )
        )

        val (_, incoming) = provider.getDependencies(targetFqn, false)

        assertEquals(1, incoming.size, "Should have 1 incoming dependency")
        assertEquals(userFqn, incoming[0].classFqn)
        assertEquals(DependencyType.FIELD_TYPE, incoming[0].dependencyType)
        assertEquals("target", incoming[0].location)
    }

    @Test
    fun `getDependencies should filter by includeLibraries flag`() {
        val libraryFqn = "com.library.LibraryClass"
        val projectFqn = "com.example.ProjectClass"

        classesMap[libraryFqn] = createClassInfo(
            fqn = libraryFqn,
            simpleName = "LibraryClass",
            packageName = "com.library",
            source = ClassSource.LIBRARY
        )
        classesMap[projectFqn] = createClassInfo(
            fqn = projectFqn,
            simpleName = "ProjectClass",
            packageName = "com.example",
            source = ClassSource.PROJECT,
            fields = listOf(
                FieldInfo(name = "lib", type = libraryFqn, visibility = Visibility.PRIVATE)
            )
        )

        // Without libraries
        val (outgoingNoLib, _) = provider.getDependencies(projectFqn, includeLibraries = false)
        assertTrue(outgoingNoLib.none { it.classFqn == libraryFqn },
            "Should not include library dependency when includeLibraries=false")

        // With libraries
        val (outgoingWithLib, _) = provider.getDependencies(projectFqn, includeLibraries = true)
        assertTrue(outgoingWithLib.any { it.classFqn == libraryFqn },
            "Should include library dependency when includeLibraries=true")
    }

    @Test
    fun `getDependencies should not return duplicates`() {
        val sharedTypeFqn = "com.example.SharedType"
        val consumerFqn = "com.example.Consumer"

        classesMap[sharedTypeFqn] = createClassInfo(
            fqn = sharedTypeFqn,
            simpleName = "SharedType",
            packageName = "com.example"
        )
        // Class uses SharedType in field AND as method parameter
        classesMap[consumerFqn] = createClassInfo(
            fqn = consumerFqn,
            simpleName = "Consumer",
            packageName = "com.example",
            fields = listOf(
                FieldInfo(name = "shared", type = sharedTypeFqn, visibility = Visibility.PRIVATE)
            ),
            methods = listOf(
                MethodInfo(
                    name = "process",
                    visibility = Visibility.PUBLIC,
                    returnType = "void",
                    parameters = listOf(
                        ParameterInfo(name = "input", type = sharedTypeFqn)
                    )
                )
            )
        )

        val (outgoing, _) = provider.getDependencies(consumerFqn, false)

        // Should have 2 distinct entries: field and method param (different locations)
        val sharedTypeDeps = outgoing.filter { it.classFqn == sharedTypeFqn }
        assertEquals(2, sharedTypeDeps.size, "Should have 2 distinct dependencies to SharedType")
        assertTrue(sharedTypeDeps.any { it.dependencyType == DependencyType.FIELD_TYPE })
        assertTrue(sharedTypeDeps.any { it.dependencyType == DependencyType.METHOD_PARAMETER })
    }

    @Test
    fun `getDependencies should handle class with no dependencies`() {
        val simpleFqn = "com.example.SimpleClass"

        classesMap[simpleFqn] = createClassInfo(
            fqn = simpleFqn,
            simpleName = "SimpleClass",
            packageName = "com.example"
            // No fields, methods, or custom superclass
        )

        val (outgoing, incoming) = provider.getDependencies(simpleFqn, false)

        // Should have no outgoing (java.lang.Object is excluded)
        assertTrue(outgoing.isEmpty(), "Should have no outgoing dependencies")
        assertTrue(incoming.isEmpty(), "Should have no incoming dependencies")
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
        interfaces: List<String> = emptyList(),
        fields: List<FieldInfo> = emptyList(),
        methods: List<MethodInfo> = emptyList()
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
            interfaces = interfaces,
            fields = fields,
            methods = methods
        )
    }
}
