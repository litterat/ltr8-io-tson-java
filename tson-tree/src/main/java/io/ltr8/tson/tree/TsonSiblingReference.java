package io.ltr8.tson.tree;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A sibling reference read with no schema ([TSON-DATA] §7.4, {@link TsonReference}): {@code kind!} kept as the
 * structured value it is, the {@link #field} it names and the mark, resolving nothing. Its meaning, the type the field
 * {@code field} of the same record names, is a schema's to give, and only at a record field's type.
 *
 * <p>{@link #text} recreates the text, {@code kind!}.
 */
public record TsonSiblingReference(String field, Optional<String> typeRef, List<TsonAnnotation> annotations)
        implements TsonReference {

    public TsonSiblingReference {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(typeRef, "typeRef");
        annotations = List.copyOf(annotations);
        if (field.isEmpty()) {
            throw new IllegalArgumentException("a sibling reference names a field");
        }
    }

    /** The field's name and the sibling mark: {@code kind!}. */
    @Override
    public String text() {
        return field + "!";
    }

    /** The text -- what the reference reads as. */
    @Override
    public String toString() {
        return text();
    }
}
