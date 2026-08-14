package io.ltr8.tson.tree;

import java.util.List;
import java.util.Optional;

/**
 * A map node -- ordered key→value entries whose <b>keys are themselves nodes</b> (TSON map keys can be typed,
 * §2.6), unlike a record's string field names. {@link #get(String)} is a convenience matching an entry whose
 * key is an atom equal to the given string; {@link #entries()} exposes the full, typed-key form.
 */
public record TsonMap(List<Entry> entries, Optional<String> typeRef, List<TsonAnnotation> annotations)
        implements TsonValue {

    public record Entry(TsonValue key, TsonValue value) {
    }

    public TsonMap {
        entries = List.copyOf(entries);
        annotations = List.copyOf(annotations);
    }

    public static TsonMap of(List<Entry> entries) {
        return new TsonMap(entries, Optional.empty(), List.of());
    }

    @Override
    public boolean isMap() {
        return true;
    }

    /** The value whose key is a string-valued atom equal to {@code name}, or {@link TsonMissing}. */
    @Override
    public TsonValue get(String name) {
        for (Entry entry : entries) {
            if (entry.key().asString().filter(name::equals).isPresent()) {
                return entry.value();
            }
        }
        return TsonMissing.instance();
    }
}
