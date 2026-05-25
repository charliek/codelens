package codelens.classgraph.fixtures;

import java.util.ArrayList;
import java.util.List;

/**
 * Test fixture with deliberately predictable bytecode for CallSiteExtractor.
 *
 * Written in Java (not Kotlin) so the emitted invocations and constants are
 * stable and free of compiler-inserted intrinsics. Exercises: constructor
 * calls ({@code <init>}), an {@code invokevirtual} with a string constant, an
 * {@code invokeinterface} with a string constant, and a call carrying both a
 * class-literal and a string constant.
 */
public class CallSiteJavaSample {

    public Object makeCalls() {
        StringBuilder sb = new StringBuilder(); // NEW + INVOKESPECIAL <init>
        sb.append("alpha"); // INVOKEVIRTUAL StringBuilder.append, const "alpha"

        List<String> list = new ArrayList<>(); // NEW + INVOKESPECIAL <init>
        list.add("beta"); // INVOKEINTERFACE List.add, const "beta"

        Class<?> cls = String.class; // LDC Ljava/lang/String;
        register("gamma", cls); // INVOKEVIRTUAL register, const "gamma" + class java.lang.String

        return sb.toString();
    }

    private void register(String name, Class<?> type) {
        // no-op; exists so makeCalls() has a call carrying a class-literal arg
    }

    public int noCalls() {
        return 7;
    }
}
