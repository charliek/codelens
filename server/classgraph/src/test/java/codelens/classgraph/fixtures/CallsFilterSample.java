package codelens.classgraph.fixtures;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Test fixture for the {@code calls} enclosing-method filters (#44).
 *
 * Two overloads named {@code load} differ in parameter and return type, so the
 * {@code (name, descriptor)} filter must disambiguate them — locking that the
 * filter does not over-match overloads, and that ClassGraph's method descriptor
 * matches the ASM descriptor the extractor records. One overload is annotated to
 * exercise {@code --in-methods-annotated}.
 */
public class CallsFilterSample {

    // Returns String; descriptor (Ljava/lang/String;)Ljava/lang/String;.
    @FilterMarker
    public String load(String key) {
        return key.trim(); // INVOKEVIRTUAL java/lang/String.trim
    }

    // Same name, returns Integer; descriptor (I)Ljava/lang/Integer;.
    public Integer load(int id) {
        return Integer.valueOf(id); // INVOKESTATIC java/lang/Integer.valueOf
    }
}

@Retention(RetentionPolicy.RUNTIME)
@interface FilterMarker {
}
