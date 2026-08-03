package io.ltr8.tson.tree;

import java.util.List;
import java.util.Optional;

/**
 * A map node -- ordered key→value entries whose <b>keys are themselves nodes</b> (TSON map keys can be typed,
 * §2.6), unlike a record's string field names. {@link #get(String)} is a convenience matching an entry whose
 * key is an atom equal to the given string; {@link #entries()} exposes the full, typed-key form.
 */
public record MapNode(List<Entry> entries, Optional<String> typeRef, List<TsonAnnotation> annotations)
        implements TsonNode {

    public record Entry(TsonNode key, TsonNode value) {
    }

    public MapNode {
        entries = List.copyOf(entries);
        annotations = List.copyOf(annotations);
    }

    public static MapNode of(List<Entry> entries) {
        return new MapNode(entries, Optional.empty(), List.of());
    }

    @Override
    public boolean isMap() {
        return true;
    }

    /** The value whose key is a string-valued atom equal to {@code name}, or {@link MissingNode}. */
    @Override
    public TsonNode get(String name) {
        for (Entry entry : entries) {
            if (entry.key().asString().filter(name::equals).isPresent()) {
                return entry.value();
            }
        }
        return MissingNode.instance();
    }
}
