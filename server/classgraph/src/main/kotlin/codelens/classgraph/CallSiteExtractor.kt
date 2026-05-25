package codelens.classgraph

import codelens.core.model.CallSite
import codelens.core.model.CallSiteList
import codelens.core.model.ConstantArg
import codelens.core.model.ConstantKind
import codelens.core.model.MethodCalls
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.slf4j.LoggerFactory

/**
 * Extracts the invocations a method makes, directly from bytecode, using ASM.
 *
 * This is the general "forward" call-site primitive: a linear instruction scan
 * with a sliding window of recently-loaded constants (`LDC`). Each invocation
 * instruction (`invokevirtual` / `invokestatic` / `invokeinterface` /
 * `invokespecial`) becomes a [CallSite] carrying the constants seen since the
 * previous invocation. It returns *raw facts* — every invocation, with no
 * framework-specific filtering.
 *
 * Lambdas and method references compile to `invokedynamic`. The scan reads the
 * `LambdaMetafactory` bootstrap and resolves the implementation method (the
 * synthetic `lambda$…` body, or the referenced method for a method reference),
 * recording it on the [CallSite] via [CallSite.invokeDynamic] /
 * [CallSite.implMethodName]. `StringConcatFactory` invokedynamics (string
 * concatenation) are recognized and skipped. The constant window is *not*
 * cleared at an `invokedynamic`: a constant loaded before a lambda argument
 * (e.g. a framework route path) must survive to the call that consumes it.
 *
 * The scan technique (track `LDC` constants, correlate with subsequent
 * `INVOKE`s) is generalized from an earlier framework-specific route extractor:
 * where that walked a single method and recorded only calls on one interface,
 * this records every invocation in every (or one) method.
 */
class CallSiteExtractor(
    private val classGraphProvider: ClassGraphProvider,
) {
    private val logger = LoggerFactory.getLogger(CallSiteExtractor::class.java)

    /**
     * Extract call sites for a class.
     *
     * @param fqn Fully qualified class name.
     * @param methodName When non-null, only this method is scanned.
     * @param descriptor When non-null (and [methodName] is set), disambiguates
     *   overloads by exact JVM descriptor match.
     * @return Per-method call lists. When [methodName] is null, methods that
     *   make no calls are omitted. When a specific method is requested, a
     *   matching method is included even if it makes no calls (one entry with
     *   an empty call list), while an unknown method yields no entries — so the
     *   caller can tell "found but makes no calls" from "not found".
     */
    fun extract(
        fqn: String,
        methodName: String? = null,
        descriptor: String? = null,
    ): CallSiteList {
        val classBytes = classGraphProvider.getClassBytes(fqn)
        if (classBytes == null) {
            logger.debug("Could not get class bytes for $fqn")
            return CallSiteList(fqn, emptyList())
        }

        return try {
            val collected = mutableListOf<MethodCalls>()
            val reader = ClassReader(classBytes)
            reader.accept(
                CallExtractingClassVisitor(methodName, descriptor, collected),
                ClassReader.SKIP_FRAMES,
            )
            // For the whole-class view, drop methods that make no calls to keep
            // the output focused. For a targeted method query, keep the empty
            // result so the caller can distinguish "makes no calls" from
            // "method not found".
            val methods =
                if (methodName == null) {
                    collected.filter { it.calls.isNotEmpty() }
                } else {
                    collected
                }
            CallSiteList(fqn, methods)
        } catch (e: Exception) {
            logger.warn("Failed to extract calls from $fqn: ${e.message}")
            CallSiteList(fqn, emptyList())
        }
    }

    private class CallExtractingClassVisitor(
        private val methodFilter: String?,
        private val descriptorFilter: String?,
        private val out: MutableList<MethodCalls>,
    ) : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            if (name == null) return null
            if (methodFilter != null && name != methodFilter) return null
            if (descriptorFilter != null && descriptor != descriptorFilter) return null

            val calls = mutableListOf<CallSite>()
            out.add(MethodCalls(name, descriptor ?: "", calls))
            return CallExtractingMethodVisitor(calls)
        }
    }

    /**
     * Tracks recent `LDC` constants and emits a [CallSite] for each invocation,
     * attaching the constants observed since the previous invocation.
     */
    private class CallExtractingMethodVisitor(
        private val calls: MutableList<CallSite>,
    ) : MethodVisitor(Opcodes.ASM9) {
        private val recentConstants = ArrayDeque<ConstantArg>()
        private var currentLine: Int? = null

        companion object {
            /** Cap on remembered constants, mirroring the original extractor's window. */
            private const val MAX_WINDOW = 16
        }

        override fun visitLineNumber(
            line: Int,
            start: Label?,
        ) {
            currentLine = line
        }

        override fun visitLdcInsn(value: Any?) {
            toConstantArg(value)?.let { push(it) }
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?,
            isInterface: Boolean,
        ) {
            if (owner == null || name == null) {
                recentConstants.clear()
                return
            }
            calls.add(
                CallSite(
                    ownerType = Type.getObjectType(owner).className,
                    methodName = name,
                    descriptor = descriptor ?: "",
                    isInterface = isInterface,
                    constantArgs = recentConstants.toList(),
                    lineNumber = currentLine,
                ),
            )
            // Constants belong to the call that just consumed them; reset so the
            // next call only sees constants loaded after this point.
            recentConstants.clear()
        }

        override fun visitInvokeDynamicInsn(
            name: String?,
            descriptor: String?,
            bootstrapMethodHandle: Handle?,
            vararg bootstrapMethodArguments: Any?,
        ) {
            // Note: the constant window is intentionally NOT cleared here. A lambda
            // creation does not consume the constants destined for the call that
            // takes the lambda (e.g. `chain.path("users", ctx -> …)` — "users"
            // must survive the invokedynamic to reach the `path` call).
            if (name == null || descriptor == null || bootstrapMethodHandle == null) return

            // Only LambdaMetafactory bootstraps (metafactory / altMetafactory) denote
            // a lambda or method reference. Everything else — notably
            // StringConcatFactory (string concatenation) — is not a call we model.
            if (bootstrapMethodHandle.owner != "java/lang/invoke/LambdaMetafactory") return

            // For both metafactory and altMetafactory the second bootstrap argument
            // is the implementation-method Handle (owner/name/desc + a tag marking
            // a synthetic lambda vs a method-reference target).
            val implHandle = bootstrapMethodArguments.getOrNull(1) as? Handle ?: return

            calls.add(
                CallSite(
                    // The functional-interface (SAM) type produced by the indy is its return type.
                    ownerType = Type.getReturnType(descriptor).className,
                    // The functional-interface method being implemented (e.g. "run", "handle").
                    methodName = name,
                    descriptor = descriptor,
                    isInterface = false,
                    // The window belongs to the consuming call, not the lambda creation.
                    constantArgs = emptyList(),
                    lineNumber = currentLine,
                    invokeDynamic = true,
                    implMethodOwner = Type.getObjectType(implHandle.owner).className,
                    implMethodName = implHandle.name,
                ),
            )
        }

        private fun push(arg: ConstantArg) {
            if (recentConstants.size >= MAX_WINDOW) {
                recentConstants.removeFirst()
            }
            recentConstants.addLast(arg)
        }

        private fun toConstantArg(value: Any?): ConstantArg? =
            when (value) {
                is String -> ConstantArg(ConstantKind.STRING, value)
                is Int -> ConstantArg(ConstantKind.INT, value.toString())
                is Long -> ConstantArg(ConstantKind.LONG, value.toString())
                is Float -> ConstantArg(ConstantKind.FLOAT, value.toString())
                is Double -> ConstantArg(ConstantKind.DOUBLE, value.toString())
                is Type ->
                    if (value.sort == Type.OBJECT || value.sort == Type.ARRAY) {
                        ConstantArg(ConstantKind.CLASS, value.className)
                    } else {
                        null
                    }
                else -> null
            }
    }
}
