package codelens.classgraph

import codelens.core.model.ClassInfo
import codelens.core.model.ClassSource
import codelens.core.model.XrefKind
import codelens.core.model.XrefReference

/**
 * Builds the inverse cross-reference for a type: every place across the scanned
 * classes that references it.
 *
 * Two passes, both driven through [ClassGraphProvider]:
 *  - **signature-level** (cheap, from [ClassInfo]): EXTENDS, IMPLEMENTS, FIELD,
 *    PARAM, RETURN, ANNOTATION. Honors [includeLibraries].
 *  - **bytecode-level** (reuses the call-site scan): INSTANTIATION and
 *    CALL_RECEIVER. Always restricted to project classes — reading library
 *    bytecode wholesale is impractical.
 *
 * Returns raw, unpaginated references; narrowing (kind filter, pagination,
 * aggregates) is applied by the caller.
 */
class TypeXrefAnalyzer(
    private val provider: ClassGraphProvider,
) {
    fun analyze(
        typeFqn: String,
        includeLibraries: Boolean,
        scopeImplementing: String?,
    ): List<XrefReference> {
        // Optional intersect: only count references from classes that implement
        // (or extend) scopeImplementing — "classes that implement X and reference Y".
        val scope: Set<String>? =
            scopeImplementing?.let { iface ->
                val (direct, indirect) = provider.getImplementations(iface, includeLibraries)
                (direct + indirect).map { it.fqn }.toSet()
            }

        val refs = mutableListOf<XrefReference>()
        for ((fqn, info) in provider.getAllClasses()) {
            if (fqn == typeFqn) continue // a type doesn't cross-reference itself
            if (!includeLibraries && info.source != ClassSource.PROJECT) continue
            if (scope != null && fqn !in scope) continue

            collectSignatureRefs(typeFqn, info, refs)
            if (info.source == ClassSource.PROJECT) {
                collectBytecodeRefs(typeFqn, info, refs)
            }
        }
        return refs
    }

    private fun collectSignatureRefs(
        typeFqn: String,
        info: ClassInfo,
        out: MutableList<XrefReference>,
    ) {
        // Supertypes: the base (superclass / each interface) plus any generic type
        // arguments, e.g. Foo in `extends Base<Foo>` or `implements Comparable<Foo>`.
        // Guard the type-argument check against the base so `Foo<Foo>` is not double-counted.
        if (info.superclass == typeFqn) {
            out.add(ref(info, XrefKind.EXTENDS))
        } else if (typeFqn in info.superclassTypeArgs) {
            out.add(ref(info, XrefKind.EXTENDS))
        }
        if (info.interfaces.contains(typeFqn)) {
            out.add(ref(info, XrefKind.IMPLEMENTS))
        } else if (typeFqn in info.interfaceTypeArgs) {
            out.add(ref(info, XrefKind.IMPLEMENTS))
        }

        for (field in info.fields) {
            if (references(typeFqn, field.type, field.typeRefs)) {
                out.add(ref(info, XrefKind.FIELD, member = field.name, detail = field.type))
            }
        }

        for (method in info.methods) {
            if (method.isSynthetic) continue
            if (references(typeFqn, method.returnType, method.returnTypeRefs)) {
                out.add(ref(info, XrefKind.RETURN, member = method.name, detail = method.returnType))
            }
            for (param in method.parameters) {
                if (references(typeFqn, param.type, param.typeRefs)) {
                    out.add(ref(info, XrefKind.PARAM, member = method.name, detail = param.type))
                }
            }
        }
        for (ctor in info.constructors) {
            if (ctor.isSynthetic) continue
            for (param in ctor.parameters) {
                if (references(typeFqn, param.type, param.typeRefs)) {
                    out.add(ref(info, XrefKind.PARAM, member = "<init>", detail = param.type))
                }
            }
        }

        // Annotation usages (class, method, field, constructor levels).
        if (info.annotations.any { it.type == typeFqn }) {
            out.add(ref(info, XrefKind.ANNOTATION, detail = "class"))
        }
        for (method in info.methods) {
            if (method.annotations.any { it.type == typeFqn }) {
                out.add(ref(info, XrefKind.ANNOTATION, member = method.name, detail = "method"))
            }
        }
        for (field in info.fields) {
            if (field.annotations.any { it.type == typeFqn }) {
                out.add(ref(info, XrefKind.ANNOTATION, member = field.name, detail = "field"))
            }
        }
        for (ctor in info.constructors) {
            if (ctor.annotations.any { it.type == typeFqn }) {
                out.add(ref(info, XrefKind.ANNOTATION, member = "<init>", detail = "constructor"))
            }
        }
    }

    private fun collectBytecodeRefs(
        typeFqn: String,
        info: ClassInfo,
        out: MutableList<XrefReference>,
    ) {
        val calls = provider.getCalls(info.name.fqn)
        for (methodCalls in calls.methods) {
            for (call in methodCalls.calls) {
                // Skip invokedynamic (lambda / method-reference) sites: their
                // ownerType is the functional-interface type, and recording a
                // CALL_RECEIVER for it would be misleading (the class implements
                // the SAM via a lambda, it does not invoke a method on it). Types
                // genuinely used inside a lambda *body* are still attributed via
                // the synthetic `lambda$…` method, which getCalls also scans.
                if (call.invokeDynamic) continue
                if (call.ownerType != typeFqn) continue
                val isCtor = call.methodName == "<init>"
                out.add(
                    XrefReference(
                        fromFqn = info.name.fqn,
                        fromSimpleName = info.name.simpleName,
                        fromSource = info.source,
                        kind = if (isCtor) XrefKind.INSTANTIATION else XrefKind.CALL_RECEIVER,
                        member = methodCalls.methodName,
                        detail = if (isCtor) null else call.methodName,
                        lineNumber = call.lineNumber,
                    ),
                )
            }
        }
    }

    private fun ref(
        info: ClassInfo,
        kind: XrefKind,
        member: String? = null,
        detail: String? = null,
    ): XrefReference =
        XrefReference(
            fromFqn = info.name.fqn,
            fromSimpleName = info.name.simpleName,
            fromSource = info.source,
            kind = kind,
            member = member,
            detail = detail,
        )

    /**
     * True when a (possibly generic) type references [typeFqn]. [typeRefs] holds
     * the FQNs captured from the bytecode signature (container + type arguments);
     * for hand-built [ClassInfo] it is empty, so the erased base parsed from the
     * [type] display string is also checked. The union keeps results correct
     * whether or not a generic signature was captured.
     */
    private fun references(
        typeFqn: String,
        type: String,
        typeRefs: List<String>,
    ): Boolean = typeFqn in typeRefs || baseType(type) == typeFqn

    /** Strip array brackets and generic parameters to the base type FQN. */
    private fun baseType(type: String): String = type.replace("[]", "").substringBefore("<").trim()
}
