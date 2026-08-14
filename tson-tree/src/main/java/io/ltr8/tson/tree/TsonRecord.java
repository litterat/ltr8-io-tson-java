package io.ltr8.tson.tree;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A record node -- named fields in declaration order (§8.1). {@link #get(String)} looks a field up by name;
 * {@link #fields()} exposes the ordered map. Duplicate field names are already resolved ("last value wins",
 * §2.5) before a tree is built, so names are unique here.
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
