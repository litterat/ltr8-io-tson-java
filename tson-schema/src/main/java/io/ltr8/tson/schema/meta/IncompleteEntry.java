package io.ltr8.tson.schema.meta;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An entry a constructor of types built with a required field absent, {@code nonempty => !meta.array ^ { min_items:
 * 1 }} ([TSON-SCHEMA] §5.5). It is no type: nothing is typed by it, it carries no chain and no subtype names it. It
 * is a base for {@code ^} and a head to complete, and a completion is the plain construction of {@link #constructor}.
 *
 * <p>It is held in a schema's namespace so an import carries it as any entry, and never written: a resolved form
 * holds complete entries only.
 *
 * <p>{@link #written} is the binding record as the author wrote it, the parsed node, opaque here as {@code
 * TsonSchema.HeldValue.written} is: the schema model has no body whose required components may be absent, and a
 * tree would lose a token's form. {@link #read} is each written field as its declared type read it where it was
 * written, what the names it holds are judged by when its schema links.
 *
 * @param constructor the constructor of types it builds, past its alias hops
 * @param absent      the required fields of {@code constructor} it leaves absent, in declaration order, never empty
 * @param written     the binding record it writes, the fields every refinement and completion of it adds to
 * @param read        each field of {@code written} its declared type read, by name
 */
public record IncompleteEntry(String constructor, List<String> absent, Object written, Map<String, Object> read)
        implements Top {

    public IncompleteEntry {
        Objects.requireNonNull(constructor, "constructor");
        Objects.requireNonNull(written, "written");
        absent = List.copyOf(absent);
        read = Collections.unmodifiableMap(new LinkedHashMap<>(read));
        if (absent.isEmpty()) {
            throw new IllegalStateException("an entry with no required field absent is complete");
        }
    }
}
