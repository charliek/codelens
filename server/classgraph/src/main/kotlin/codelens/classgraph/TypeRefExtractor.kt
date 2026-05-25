package codelens.classgraph

import io.github.classgraph.ArrayTypeSignature
import io.github.classgraph.ClassRefTypeSignature
import io.github.classgraph.HierarchicalTypeSignature
import io.github.classgraph.TypeArgument

/**
 * Collects the class FQNs referenced by a bytecode type signature: the
 * container type plus every (recursively nested) type argument. Type variables
 * (`T`), primitives, and `void` contribute nothing.
 *
 * Generics are read from ClassGraph's walkable `TypeSignature` tree rather than
 * by parsing the `<…>` display string, so nested generics
 * (`Map<String, List<Foo>>`), arrays of generics (`List<Foo>[]`), and inner-class
 * type arguments (`Outer<X>.Inner<Y>`) are all captured.
 *
 * Returns a sorted, de-duplicated list so callers (graph edges, golden tests)
 * see stable output.
 */
internal fun referencedClassFqns(signature: HierarchicalTypeSignature?): List<String> {
    val fqns = sortedSetOf<String>()
    collectReferencedClassFqns(signature, fqns)
    return fqns.toList()
}

/**
 * The class FQNs appearing in the type arguments of a generic supertype
 * signature — e.g. `com.example.Foo` for `Base<Foo>` — excluding the base type
 * itself (which the caller already records separately). Empty when the supertype
 * is non-generic or null.
 */
internal fun typeArgumentFqns(classRef: ClassRefTypeSignature?): List<String> {
    if (classRef == null) return emptyList()
    val fqns = sortedSetOf<String>()
    classRef.typeArguments.forEach { collectReferencedClassFqns(it, fqns) }
    classRef.suffixTypeArguments.forEach { args -> args.forEach { collectReferencedClassFqns(it, fqns) } }
    return fqns.toList()
}

private fun collectReferencedClassFqns(
    signature: HierarchicalTypeSignature?,
    into: MutableSet<String>,
) {
    when (signature) {
        null -> return
        is ClassRefTypeSignature -> {
            into.add(signature.fullyQualifiedClassName)
            signature.typeArguments.forEach { collectReferencedClassFqns(it, into) }
            // Inner-class suffixes (Outer.Inner<T>) can carry their own type arguments.
            signature.suffixTypeArguments.forEach { args -> args.forEach { collectReferencedClassFqns(it, into) } }
        }
        is ArrayTypeSignature -> collectReferencedClassFqns(signature.elementTypeSignature, into)
        // null typeSignature is an unbounded wildcard (`?`) — nothing to add.
        is TypeArgument -> collectReferencedClassFqns(signature.typeSignature, into)
        // BaseTypeSignature (primitive / void) and TypeVariableSignature (`T`): nothing to add.
        else -> return
    }
}
