package io.ltr8.tson.tree;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A record node -- named fields in a stable order, with {@link #get(String)} looking one up by name and
 * {@link #fields()} exposing the ordered map. Duplicate field names are already resolved ("last value wins",
 * §2.5) before a tree is built, so names are unique here.
 *
 * <p>The order is whatever the producing reader inserted: a schema-driven read yields the fields the document
 * stated, in document order, then the ones it left to the schema (defaults and fixed values) in declaration
 * order. That is not §8.1 declaration order end to end, and a consumer needing it should sort against the
 * schema rather than trust the map.
 */
public record TsonRecord(Map<String, TsonValue> fields, Optional<String> typeRef, List<TsonAnnotation> annotations)
        implements TsonValue {

    public TsonRecord {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        annotations = List.copyOf(annotations);
    }

    public static TsonRecord of(Map<String, TsonValue> fields) {
        return new TsonRecord(fields, Optional.empty(), List.of());
    }

    @Override
    public boolean isRecord() {
        return true;
    }

    @Override
    public TsonValue get(String name) {
        TsonValue field = fields.get(name);
        return field != null ? field : TsonMissing.atField(name);
    }
}
