package codelens.classgraph.fixtures;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Fixture for the annotations-usages projection test: the same and different
 * annotations applied across class / constructor / method / field / parameter
 * targets, plus a meta-annotation pair ({@code @Composed} is itself annotated
 * with {@code @Base}) to exercise ClassGraph's meta-expansion the way Spring's
 * {@code @GetMapping} → {@code @RequestMapping} composition does.
 *
 * Written in Java (not Kotlin) so the emitted attribute values map cleanly to
 * ClassGraph's wrapper types and the fully-qualified names in the assertions are
 * unambiguous (the annotation types are top-level in this file's package).
 */
@Marker
public final class AnnotationUsageSample {

    @Tag("field")
    private final String name;

    @Marker
    public AnnotationUsageSample(@Tag("ctorParam") String name) {
        this.name = name;
    }

    @Tag("method")
    public String value(@Tag("methodParam") String input) {
        return input + name;
    }

    /** Carries only the composed annotation; querying the meta {@code @Base} must match here. */
    @Composed
    public void composed() {
    }
}

@Retention(RetentionPolicy.RUNTIME)
@interface Marker {
}

@Retention(RetentionPolicy.RUNTIME)
@interface Tag {
    String value();
}

@Retention(RetentionPolicy.RUNTIME)
@interface Base {
    String role() default "";
}

@Retention(RetentionPolicy.RUNTIME)
@Base(role = "admin")
@interface Composed {
}
