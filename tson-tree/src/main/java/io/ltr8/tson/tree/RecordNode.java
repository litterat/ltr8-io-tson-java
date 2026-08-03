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
public record RecordNode(Map<String, TsonNode> fields, Optional<String> typeRef, List<TsonAnnotation> annotations)
        implements TsonNode {

    public RecordNode {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        annotations = List.copyOf(annotations);
    }

    public static RecordNode of(Map<String, TsonNode> fields) {
        return new RecordNode(fields, Optional.empty(), List.of());
    }

    @Override
    public boolean isRecord() {
        return true;
    }

    @Override
    public TsonNode get(String name) {
        return fields.getOrDefault(name, MissingNode.instance());
    }
}
