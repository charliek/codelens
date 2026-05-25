package codelens.classgraph.fixtures;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** A plain type used as a generic argument throughout the fixture. */
class GenericItem {
}

/** A generic base class, parameterized by the fixture below. */
class GenericBase<T> {
}

/** A generic marker interface, parameterized by the fixture below. */
interface GenericMarker<T> {
}

/**
 * Test fixture exercising generic type-argument capture in xref / deps. It has a
 * generic superclass and interface, a parameterized field (including a nested
 * generic), and parameterized method return / parameter types — all with
 * {@link GenericItem} as the type argument. Before type-argument capture only the
 * container types (Map / List / Collection / the raw supertypes) were recorded;
 * now {@code GenericItem} is referenced through every one of them.
 */
public class GenericsSample extends GenericBase<GenericItem> implements GenericMarker<GenericItem> {

    private Map<String, GenericItem> cache;
    private Map<String, List<GenericItem>> nested;

    public List<GenericItem> getItems() {
        return null;
    }

    public void addAll(Collection<GenericItem> items) {
    }
}
