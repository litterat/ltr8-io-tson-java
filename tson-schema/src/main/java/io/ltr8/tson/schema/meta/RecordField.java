package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;

import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.Record;
import java.util.Objects;
import java.util.Optional;

/**
 * The meta-kernel's {@code record_field} record (Part 2 §5.2, §8.1): {@code name}/{@code type} are
 * REQUIRED; {@code state}, bound through plain generic binding, always appears in written output
 * even at its nominal {@link FieldState#REQUIRED} default. {@code value}/{@code valueParam} are
 * the record_field's own OPTIONAL group -- at most one present, never both.
 */
public record RecordField(String name, TypeRef type, FieldState state,
                           Optional<Token> value, @Field("value_param") Optional<String> valueParam,
                           Annotations annotations) {

    @Record
    public RecordField {
        annotations = annotations == null ? Annotations.empty() : annotations;
    }

    /** Same as the canonical constructor with no annotations -- every caller that has none to carry. */
    public RecordField(String name, TypeRef type, FieldState state, Optional<Token> value,
                        Optional<String> valueParam) {
        this(name, type, state, value, valueParam, Annotations.empty());
    }

    /** A plain {@code REQUIRED} field with no default/fixed value and no parameter routing. */
    public static RecordField required(String name, TypeRef type) {
        return new RecordField(name, type, FieldState.REQUIRED, Optional.empty(), Optional.empty());
    }

    /** A copy of this field with {@code annotations} replaced -- every other component unchanged. */
    public RecordField withAnnotations(Annotations annotations) {
        return new RecordField(name, type, state, value, valueParam, annotations);
    }

    /** Excludes {@code annotations} -- metadata does not change a field's identity, as on {@code TypeDefinition}. */
    @Override
    public boolean equals(Object o) {
        return o instanceof RecordField other
                && Objects.equals(name, other.name)
                && Objects.equals(type, other.type)
                && state == other.state
                && Objects.equals(value, other.value)
                && Objects.equals(valueParam, other.valueParam);
    }

    /** Excludes {@code annotations} -- see {@link #equals}. */
    @Override
    public int hashCode() {
        return Objects.hash(name, type, state, value, valueParam);
    }
}
