package codelens.classgraph.fixtures;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Test fixture exercising every {@code AnnotationValue} kind for the structured
 * annotation-value converter (#41).
 *
 * Written in Java so the emitted annotation attribute values are stable and map
 * cleanly to ClassGraph's wrapper types (AnnotationEnumValue, AnnotationClassRef,
 * nested AnnotationInfo, primitive/object arrays). The annotation and enum types
 * are top-level (not nested) so their fully-qualified names are unambiguous in
 * the assertions.
 */
public final class AnnotationValueSample {

    /** Carries one explicitly-set value of every kind. */
    @RichAnnotation(
        name = "alpha",
        paths = {"/a", "/b"},
        codes = {1, 2, 3},
        colors = {AnnotationColor.RED, AnnotationColor.BLUE},
        target = String.class,
        nested = @NestedAnnotation(label = "inner", order = 7),
        flag = true
    )
    public static final class Annotated {
    }

    /** Sets an empty array explicitly so the empty-ARRAY case is bytecode-backed. */
    @RichAnnotation(name = "empty", paths = {})
    public static final class EmptyArrays {
    }

    private AnnotationValueSample() {
    }
}

enum AnnotationColor {
    RED,
    GREEN,
    BLUE
}

@Retention(RetentionPolicy.RUNTIME)
@interface NestedAnnotation {
    String label() default "";

    int order() default 0;
}

@Retention(RetentionPolicy.RUNTIME)
@interface RichAnnotation {
    String name();

    String[] paths() default {};

    int[] codes() default {};

    AnnotationColor[] colors() default {};

    Class<?> target() default Object.class;

    NestedAnnotation nested() default @NestedAnnotation;

    boolean flag() default false;
}
