package codelens.classgraph.ratpack

import codelens.classgraph.ClassGraphProvider
import codelens.core.model.ClassInfo
import codelens.core.model.ClassSource
import codelens.core.model.ratpack.*
import org.slf4j.LoggerFactory

/**
 * Detects and analyzes Guice modules and their bindings.
 *
 * Detects:
 * - Classes extending AbstractModule
 * - Classes extending ConfigurableModule
 * - @Provides methods
 * - @ProvidesIntoSet / @ProvidesIntoMap multibindings
 * - bind().to() patterns (inferred from method signatures)
 */
class GuiceModuleDetector(
    private val classGraphProvider: ClassGraphProvider
) {
    private val logger = LoggerFactory.getLogger(GuiceModuleDetector::class.java)

    /**
     * Find all Guice modules in the scanned codebase.
     *
     * @param includeLibraries Include modules from library dependencies
     * @return List of module summaries
     */
    fun findAllModules(includeLibraries: Boolean = false): List<GuiceModuleSummary> {
        val modules = mutableListOf<GuiceModuleSummary>()

        // Find AbstractModule implementations
        val (directAbstract, indirectAbstract) = classGraphProvider.getImplementations(
            RatpackTypes.ABSTRACT_MODULE,
            includeLibraries
        )

        // Find ConfigurableModule implementations
        val (directConfigurable, indirectConfigurable) = classGraphProvider.getImplementations(
            RatpackTypes.CONFIGURABLE_MODULE,
            includeLibraries
        )

        // Process all module implementations
        val allModuleFqns = mutableSetOf<String>()
        (directAbstract + indirectAbstract + directConfigurable + indirectConfigurable).forEach {
            allModuleFqns.add(it.fqn)
        }

        for (fqn in allModuleFqns) {
            val classInfo = classGraphProvider.getClass(fqn) ?: continue
            if (!includeLibraries && classInfo.source != ClassSource.PROJECT) continue

            val moduleType = determineModuleType(classInfo)
            val providesMethods = countProvidesMethods(classInfo)
            val bindingCount = estimateBindingCount(classInfo)

            modules.add(
                GuiceModuleSummary(
                    fqn = classInfo.name.fqn,
                    simpleName = classInfo.name.simpleName,
                    packageName = classInfo.name.packageName,
                    moduleType = moduleType,
                    bindingCount = bindingCount,
                    providesMethodCount = providesMethods
                )
            )
        }

        // Also find classes with @Provides methods that don't extend Module
        val providesClasses = findClassesWithProvides(includeLibraries)
        for (fqn in providesClasses) {
            if (fqn in allModuleFqns) continue // Already processed

            val classInfo = classGraphProvider.getClass(fqn) ?: continue
            val providesMethods = countProvidesMethods(classInfo)

            modules.add(
                GuiceModuleSummary(
                    fqn = classInfo.name.fqn,
                    simpleName = classInfo.name.simpleName,
                    packageName = classInfo.name.packageName,
                    moduleType = GuiceModuleType.PROVIDER_CLASS,
                    bindingCount = 0,
                    providesMethodCount = providesMethods
                )
            )
        }

        return modules.sortedBy { it.fqn }
    }

    /**
     * Get detailed information about a specific module.
     *
     * @param fqn Fully qualified class name
     * @return Module info, or null if not found or not a module
     */
    fun getModuleDetail(fqn: String): GuiceModuleInfo? {
        val classInfo = classGraphProvider.getClass(fqn) ?: return null

        val moduleType = determineModuleType(classInfo)
        if (moduleType == GuiceModuleType.PROVIDER_CLASS && countProvidesMethods(classInfo) == 0) {
            logger.debug("Class $fqn is not a recognized Guice module")
            return null
        }

        // Extract @Provides methods
        val providesMethods = extractProvidesMethods(classInfo)

        // Extract bindings from configure() method (heuristic)
        val bindings = extractBindings(classInfo)

        // Find installed modules
        val installedModules = findInstalledModules(classInfo)

        // Get config type for ConfigurableModule
        val configType = extractConfigType(classInfo)

        return GuiceModuleInfo(
            fqn = classInfo.name.fqn,
            simpleName = classInfo.name.simpleName,
            packageName = classInfo.name.packageName,
            moduleType = moduleType,
            configType = configType,
            bindings = bindings,
            providesMethods = providesMethods,
            installedModules = installedModules
        )
    }

    /**
     * Find all bindings for a specific type across all modules.
     *
     * @param typeFqn Fully qualified name of the type to find bindings for
     * @return List of modules and bindings that provide this type
     */
    fun findBindingsForType(typeFqn: String): List<Pair<String, GuiceBinding>> {
        val results = mutableListOf<Pair<String, GuiceBinding>>()

        val modules = findAllModules(includeLibraries = false)

        for (moduleSummary in modules) {
            val moduleInfo = getModuleDetail(moduleSummary.fqn) ?: continue

            // Check bindings
            for (binding in moduleInfo.bindings) {
                if (binding.boundType == typeFqn || binding.toType == typeFqn) {
                    results.add(moduleSummary.fqn to binding)
                }
            }

            // Check @Provides methods
            for (provides in moduleInfo.providesMethods) {
                if (provides.providesType == typeFqn) {
                    results.add(
                        moduleSummary.fqn to GuiceBinding(
                            boundType = provides.providesType,
                            toType = null,
                            scope = provides.scope,
                            isMultibinding = provides.intoSet || provides.intoMap,
                            bindingSource = when {
                                provides.intoSet -> BindingSource.PROVIDES_INTO_SET
                                provides.intoMap -> BindingSource.PROVIDES_INTO_MAP
                                else -> BindingSource.PROVIDES
                            }
                        )
                    )
                }
            }
        }

        return results
    }

    /**
     * Determine the type of Guice module.
     */
    private fun determineModuleType(classInfo: ClassInfo): GuiceModuleType {
        return when {
            extendsClass(classInfo, RatpackTypes.CONFIGURABLE_MODULE) ->
                GuiceModuleType.CONFIGURABLE_MODULE

            extendsClass(classInfo, RatpackTypes.ABSTRACT_MODULE) ->
                GuiceModuleType.ABSTRACT_MODULE

            else -> GuiceModuleType.PROVIDER_CLASS
        }
    }

    /**
     * Check if a class extends a specific superclass (directly or indirectly).
     */
    private fun extendsClass(classInfo: ClassInfo, targetSuperclass: String): Boolean {
        var current: ClassInfo? = classInfo
        val visited = mutableSetOf<String>()

        while (current != null) {
            if (current.name.fqn in visited) break
            visited.add(current.name.fqn)

            if (current.superclass == targetSuperclass) {
                return true
            }
            current = current.superclass?.let { classGraphProvider.getClass(it) }
        }
        return false
    }

    /**
     * Count @Provides methods in a class.
     */
    private fun countProvidesMethods(classInfo: ClassInfo): Int {
        return classInfo.methods.count { method ->
            method.annotations.any { ann ->
                ann.type == RatpackTypes.PROVIDES ||
                    ann.type == RatpackTypes.PROVIDES_INTO_SET ||
                    ann.type == RatpackTypes.PROVIDES_INTO_MAP
            }
        }
    }

    /**
     * Estimate binding count from class structure.
     * Note: Without bytecode analysis, this is heuristic based on configure() method presence.
     */
    private fun estimateBindingCount(classInfo: ClassInfo): Int {
        // Look for configure() method - indicates module has bindings
        val hasConfigure = classInfo.methods.any {
            it.name == "configure" && it.parameters.isEmpty()
        }
        return if (hasConfigure) 1 else 0 // Placeholder - real count would need bytecode analysis
    }

    /**
     * Extract @Provides method information.
     */
    private fun extractProvidesMethods(classInfo: ClassInfo): List<ProvidesMethodInfo> {
        return classInfo.methods
            .filter { method ->
                method.annotations.any { ann ->
                    ann.type == RatpackTypes.PROVIDES ||
                        ann.type == RatpackTypes.PROVIDES_INTO_SET ||
                        ann.type == RatpackTypes.PROVIDES_INTO_MAP
                }
            }
            .map { method ->
                val isIntoSet = method.annotations.any { it.type == RatpackTypes.PROVIDES_INTO_SET }
                val isIntoMap = method.annotations.any { it.type == RatpackTypes.PROVIDES_INTO_MAP }

                val scope = method.annotations.find { it.type in RatpackTypes.SCOPE_ANNOTATIONS }?.type

                ProvidesMethodInfo(
                    methodName = method.name,
                    providesType = cleanTypeName(method.returnType),
                    scope = scope,
                    intoSet = isIntoSet,
                    intoMap = isIntoMap,
                    dependencies = method.parameters.map { cleanTypeName(it.type) }
                )
            }
    }

    /**
     * Extract bindings from module.
     * Note: This is limited without bytecode analysis - we can only infer from method signatures.
     */
    private fun extractBindings(classInfo: ClassInfo): List<GuiceBinding> {
        val bindings = mutableListOf<GuiceBinding>()

        // Look for methods that might indicate bindings
        // In a real implementation, we'd analyze the configure() method bytecode

        // Heuristic: Look for methods with Provider return type
        for (method in classInfo.methods) {
            if (method.returnType.contains("Provider<")) {
                val providedType = extractGenericType(method.returnType)
                if (providedType != null) {
                    bindings.add(
                        GuiceBinding(
                            boundType = providedType,
                            toType = null,
                            scope = null,
                            isMultibinding = false,
                            bindingSource = BindingSource.BIND_TO_PROVIDER
                        )
                    )
                }
            }
        }

        return bindings
    }

    /**
     * Find modules installed by this module.
     * Note: Without bytecode analysis, we look for field types that are modules.
     */
    private fun findInstalledModules(classInfo: ClassInfo): List<String> {
        val installed = mutableListOf<String>()

        // Look for fields of Module type
        for (field in classInfo.fields) {
            val fieldType = cleanTypeName(field.type)
            val fieldClass = classGraphProvider.getClass(fieldType)
            if (fieldClass != null) {
                if (extendsClass(fieldClass, RatpackTypes.ABSTRACT_MODULE) ||
                    extendsClass(fieldClass, RatpackTypes.CONFIGURABLE_MODULE)
                ) {
                    installed.add(fieldType)
                }
            }
        }

        return installed
    }

    /**
     * Extract config type for ConfigurableModule.
     */
    private fun extractConfigType(classInfo: ClassInfo): String? {
        if (!extendsClass(classInfo, RatpackTypes.CONFIGURABLE_MODULE)) {
            return null
        }

        // Look for getConfig() method return type
        val getConfigMethod = classInfo.methods.find { it.name == "getConfig" }
        if (getConfigMethod != null) {
            return cleanTypeName(getConfigMethod.returnType)
        }

        // Look for field named "config" or similar
        val configField = classInfo.fields.find { it.name.lowercase().contains("config") }
        if (configField != null) {
            return cleanTypeName(configField.type)
        }

        return null
    }

    /**
     * Find classes with @Provides methods that aren't module subclasses.
     */
    private fun findClassesWithProvides(includeLibraries: Boolean): Set<String> {
        val result = mutableSetOf<String>()

        val filter = codelens.core.model.ClassFilter(includeLibraries = includeLibraries)
        val classes = classGraphProvider.listClasses(filter)

        for (classSummary in classes) {
            val classInfo = classGraphProvider.getClass(classSummary.fqn) ?: continue
            if (countProvidesMethods(classInfo) > 0) {
                result.add(classSummary.fqn)
            }
        }

        return result
    }

    /**
     * Clean up a type name (remove generics, arrays, etc. for comparison).
     */
    private fun cleanTypeName(type: String): String {
        return type
            .substringBefore("<")
            .substringBefore("[")
            .trim()
    }

    /**
     * Extract generic type parameter from a parameterized type.
     */
    private fun extractGenericType(type: String): String? {
        val startIndex = type.indexOf("<")
        val endIndex = type.lastIndexOf(">")
        if (startIndex == -1 || endIndex == -1 || endIndex <= startIndex) {
            return null
        }
        return type.substring(startIndex + 1, endIndex).trim()
    }
}
