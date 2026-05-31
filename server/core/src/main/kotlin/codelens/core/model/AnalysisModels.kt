package codelens.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Source classification for a class.
 */
@Serializable
enum class ClassSource {
    /** Class is from the project being analyzed */
    PROJECT,

    /** Class is from a library dependency */
    LIBRARY,

    /** Class is from the JDK */
    JDK,
}

/**
 * Visibility modifier for classes/methods/fields.
 */
@Serializable
enum class Visibility {
    PUBLIC,
    PROTECTED,
    PACKAGE_PRIVATE,
    PRIVATE,
}

/**
 * Class name components.
 */
@Serializable
data class ClassName(
    /** Fully qualified name (e.g., "com.example.MyClass") */
    val fqn: String,
    /** Simple name (e.g., "MyClass") */
    val simpleName: String,
    /** Package name (e.g., "com.example") */
    val packageName: String,
)

/**
 * Information about an annotation.
 */
@Serializable
data class AnnotationInfo(
    /** Fully qualified name of the annotation type */
    val type: String,
    /**
     * Annotation attribute values, keyed by attribute name. Each value is a
     * typed [AnnotationValue]: arrays are real arrays, and enums, class
     * literals, and nested annotations are tagged via [AnnotationValue.kind] —
     * so consumers read them structurally instead of parsing stringified Java
     * values (e.g. a multi-path `@RequestMapping({"/a","/b"})` is an ARRAY of
     * STRING items, not the bracket-string `"[/a, /b]"`).
     */
    val parameters: Map<String, AnnotationValue> = emptyMap(),
)

/**
 * Discriminator for the kind of value an annotation attribute holds. Parallels
 * [ConstantKind]; BYTE/SHORT/CHAR are included so Java `byte`/`short`/`char`
 * annotation values are tagged rather than coerced to STRING.
 *
 * Enum names are part of the wire contract — consumers branch on the literal
 * strings (e.g. a skill's `jq` checks `.kind == "ENUM"`), so they are locked by
 * an enum-name stability test.
 */
@Serializable
enum class AnnotationValueKind {
    STRING,
    BOOLEAN,
    BYTE,
    SHORT,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    CHAR,
    CLASS,
    ENUM,
    ANNOTATION,
    ARRAY,
}

/**
 * A typed annotation attribute value.
 *
 * Scalars carry their text in [value] (interpret it via [kind]); [ENUM][AnnotationValueKind.ENUM]
 * additionally sets [enumType]; [CLASS][AnnotationValueKind.CLASS] puts the
 * dotted FQN (no `.class` suffix) in [value]; [ANNOTATION][AnnotationValueKind.ANNOTATION]
 * nests an [AnnotationInfo] in [annotation]; [ARRAY][AnnotationValueKind.ARRAY]
 * holds its element values in [items] (an empty array yields `items = []`).
 *
 * The optional fields are `@EncodeDefault(NEVER)` so each node serializes
 * sparsely — no `"value":null`/`"enumType":null`/`"items":null` noise — despite
 * the server's `encodeDefaults = true` (matching the idiom on [CallSite]).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AnnotationValue(
    /** What kind of value this is; tells the consumer which field(s) to read. */
    val kind: AnnotationValueKind,
    /** Scalar text, the ENUM constant name, or the CLASS dotted FQN (no `.class`). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val value: String? = null,
    /** ENUM only: the fully-qualified enum type. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val enumType: String? = null,
    /** ANNOTATION only: the nested annotation. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val annotation: AnnotationInfo? = null,
    /** ARRAY only: the element values, in order. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val items: List<AnnotationValue>? = null,
)

/**
 * Information about a method parameter.
 */
@Serializable
data class ParameterInfo(
    /** Parameter name (if available, otherwise "arg0", "arg1", etc.) */
    val name: String,
    /**
     * Fully qualified type name, in generic form when the bytecode carries a
     * signature (e.g. `java.util.List<com.example.Foo>`), otherwise erased.
     */
    val type: String,
    /** Annotations on this parameter */
    val annotations: List<AnnotationInfo> = emptyList(),
    /**
     * Every class FQN referenced by [type], including type arguments
     * (`Map<K, Foo>` → `java.util.Map`, `com.example.Foo`). Captured by walking
     * the bytecode type signature; type variables and primitives contribute
     * nothing. Server-internal (drives `xref`/`deps`); not serialized.
     */
    @Transient
    val typeRefs: List<String> = emptyList(),
)

/**
 * Information about a constructor.
 */
@Serializable
data class ConstructorInfo(
    /** Visibility modifier */
    val visibility: Visibility,
    /** Constructor parameters */
    val parameters: List<ParameterInfo> = emptyList(),
    /** Annotations on this constructor */
    val annotations: List<AnnotationInfo> = emptyList(),
    /** Is this constructor synthetic (compiler-generated)? */
    val isSynthetic: Boolean = false,
)

/**
 * Information about a method.
 */
@Serializable
data class MethodInfo(
    /** Method name */
    val name: String,
    /**
     * The method's erased JVM type descriptor (e.g. `(Ljava/lang/String;)V`).
     * Disambiguates overloads exactly — it matches the `(methodName, descriptor)`
     * key on [MethodCalls]/[CallSite], so `calls` can scope to call-sites inside
     * a specific overload.
     */
    val descriptor: String = "",
    /** Visibility modifier */
    val visibility: Visibility,
    /**
     * Return type (fully qualified name), in generic form when the bytecode
     * carries a signature (e.g. `ratpack.exec.Promise<java.lang.String>`),
     * otherwise erased.
     */
    val returnType: String,
    /** Method parameters */
    val parameters: List<ParameterInfo> = emptyList(),
    /** Annotations on this method */
    val annotations: List<AnnotationInfo> = emptyList(),
    /** Is this method static? */
    val isStatic: Boolean = false,
    /** Is this method abstract? */
    val isAbstract: Boolean = false,
    /** Is this method final? */
    val isFinal: Boolean = false,
    /** Is this method synthetic (compiler-generated)? */
    val isSynthetic: Boolean = false,
    /**
     * Every class FQN referenced by [returnType], including type arguments.
     * Captured from the bytecode type signature (type variables/primitives
     * excluded). Server-internal (drives `xref`/`deps`); not serialized.
     */
    @Transient
    val returnTypeRefs: List<String> = emptyList(),
)

/**
 * Information about a field.
 */
@Serializable
data class FieldInfo(
    /** Field name */
    val name: String,
    /** Visibility modifier */
    val visibility: Visibility,
    /**
     * Field type (fully qualified name), in generic form when the bytecode
     * carries a signature (e.g. `java.util.Map<java.lang.String, com.example.Foo>`),
     * otherwise erased.
     */
    val type: String,
    /** Annotations on this field */
    val annotations: List<AnnotationInfo> = emptyList(),
    /** Is this field static? */
    val isStatic: Boolean = false,
    /** Is this field final? */
    val isFinal: Boolean = false,
    /**
     * Every class FQN referenced by [type], including type arguments. Captured
     * from the bytecode type signature (type variables/primitives excluded).
     * Server-internal (drives `xref`/`deps`); not serialized.
     */
    @Transient
    val typeRefs: List<String> = emptyList(),
)

/**
 * Summary information about a class (for list views).
 */
@Serializable
data class ClassSummary(
    /** Fully qualified class name */
    val fqn: String,
    /** Simple name */
    val simpleName: String,
    /** Package name */
    val packageName: String,
    /** Source of the class */
    val source: ClassSource,
    /** Is this an interface? */
    val isInterface: Boolean,
    /** Is this abstract? */
    val isAbstract: Boolean,
    /** Is this an enum? */
    val isEnum: Boolean,
    /** Is this an annotation? */
    val isAnnotation: Boolean,
    /** Number of methods */
    val methodCount: Int,
    /** Number of fields */
    val fieldCount: Int,
)

/**
 * Full detailed information about a class.
 */
@Serializable
data class ClassInfo(
    /** Class name components */
    val name: ClassName,
    /** Source of the class */
    val source: ClassSource,
    /** Visibility modifier */
    val visibility: Visibility,
    /** Is this an interface? */
    val isInterface: Boolean = false,
    /** Is this abstract? */
    val isAbstract: Boolean = false,
    /** Is this final? */
    val isFinal: Boolean = false,
    /** Is this an enum? */
    val isEnum: Boolean = false,
    /** Is this an annotation? */
    val isAnnotation: Boolean = false,
    /** Is this synthetic (compiler-generated)? */
    val isSynthetic: Boolean = false,
    /** Superclass FQN (null for Object and interfaces) */
    val superclass: String? = null,
    /** List of implemented interface FQNs */
    val interfaces: List<String> = emptyList(),
    /** Annotations on this class */
    val annotations: List<AnnotationInfo> = emptyList(),
    /** Constructors in this class */
    val constructors: List<ConstructorInfo> = emptyList(),
    /** Methods in this class */
    val methods: List<MethodInfo> = emptyList(),
    /** Fields in this class */
    val fields: List<FieldInfo> = emptyList(),
    /** Path to the JAR or directory containing this class (for library source resolution) */
    val jarPath: String? = null,
    /**
     * Type-argument FQNs of the generic superclass (e.g. `com.example.Foo` for
     * `extends Base<Foo>`); the base superclass remains in [superclass]. Captured
     * from the bytecode type signature. Server-internal (drives `xref`/`deps`);
     * not serialized.
     */
    @Transient
    val superclassTypeArgs: List<String> = emptyList(),
    /**
     * Type-argument FQNs of the generic superinterfaces (flattened across all
     * interfaces, e.g. `com.example.Foo` for `implements Comparable<Foo>`); the
     * base interfaces remain in [interfaces]. Captured from the bytecode type
     * signature. Server-internal (drives `xref`/`deps`); not serialized.
     */
    @Transient
    val interfaceTypeArgs: List<String> = emptyList(),
)
