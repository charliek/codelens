package codelens.classgraph.fixtures;

import java.util.StringJoiner;
import java.util.function.Supplier;

/**
 * Test fixture with deliberately predictable {@code invokedynamic} bytecode for
 * CallSiteExtractor.
 *
 * Written in Java (not Kotlin) so lambdas, method references, and string
 * concatenation compile to the standard JDK bootstraps. Exercises:
 * <ul>
 *   <li>a lambda expression ({@code LambdaMetafactory}, synthetic
 *       {@code lambda$…} implementation method);</li>
 *   <li>a method reference ({@code LambdaMetafactory}, implementation Handle
 *       targeting {@link #provide()} directly); and</li>
 *   <li>a string concatenation ({@code StringConcatFactory}), which must be
 *       recognized and skipped, never mistaken for a call.</li>
 * </ul>
 */
public class LambdaSample {

    /**
     * A lambda expression: invokedynamic whose impl method is a synthetic
     * {@code lambda$makeLambda$0}. Its body uses {@link java.util.StringJoiner}
     * only here, so an xref of StringJoiner must attribute the reference to this
     * class via that synthetic method (the lambda-body follow-through).
     */
    public Runnable makeLambda() {
        return () -> new StringJoiner(",").add("x");
    }

    /** A method reference: invokedynamic whose impl Handle targets {@link #provide()} directly. */
    public Supplier<String> methodRef() {
        return this::provide;
    }

    /** String concatenation: compiles to a {@code StringConcatFactory} invokedynamic that must be skipped. */
    public String concat(String suffix) {
        return "value-" + suffix;
    }

    private String provide() {
        return "v";
    }
}
