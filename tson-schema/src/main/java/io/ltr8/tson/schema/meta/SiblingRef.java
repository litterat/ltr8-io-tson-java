package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Annotations;

import java.util.Objects;

/**
 * A sibling reference, {@code kind!}: a record field's type chosen, per instance, by the value of the field {@code
 * field} of the same record ([TSON-SCHEMA] §5.2, proposed). It stands only at a record field's type; see {@link
 * Reference}.
 *
 * <p>{@code field} is a field name, not a type name, so a sibling has no {@code name()} to resolve as an entry, and no
 * {@code referent} or closure: what it names differs from one instance to the next.
 *
 * <p>{@code field} is refused {@code null} or empty; {@code annotations} normalises {@code null} to empty. Identity is
 * the field alone.
 */
public record SiblingRef(String field, Annotations annotations) implements Reference {

    public SiblingRef {
        Objects.requireNonNull(field, "field");
        if (field.isEmpty()) {
            throw new IllegalArgumentException("a sibling reference names a field");
        }
        annotations = annotations == null ? Annotations.empty() : annotations;
    }

    /** The unannotated sibling reference to {@code field}. */
    public static SiblingRef of(String field) {
        return new SiblingRef(field, Annotations.empty());
    }

    /** The field's name and the sibling mark: {@code kind!}. */
    @Override
    public String text() {
        return field + "!";
    }

    @Override
    public SiblingRef withAnnotations(Annotations annotations) {
        return new SiblingRef(field, annotations);
    }

    /** By field -- {@code annotations} change no reference's identity. */
    @Override
    public boolean equals(Object o) {
        return o instanceof SiblingRef other && field.equals(other.field);
    }

    /** The hash of the field -- see {@link #equals}. */
    @Override
    public int hashCode() {
        return field.hashCode();
    }

    /** The field and the annotations. */
    @Override
    public String toString() {
        return "SiblingRef[field=" + field + ", annotations=" + annotations + "]";
    }
}
