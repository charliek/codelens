package codelens.classgraph.fixtures;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Test fixture exercising the signature-level xref kinds: EXTENDS, IMPLEMENTS,
 * FIELD, PARAM, RETURN.
 */
public class XrefSignatureSample extends ArrayList<String> implements Serializable, XrefScopeMarker {

    private Map<String, String> cache;

    public List<String> getItems(Collection<String> input) {
        return null;
    }
}
