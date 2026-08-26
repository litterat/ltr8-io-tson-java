package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.Record;
import java.util.Objects;
import java.util.Optional;

/**
 * The meta-kernel's {@code record_field} record (Part 2 §5.2, §8.1): {@code name}/{@code type} are
 * REQUIRED; {@code state}, bound through plain generic binding, always appears in written output
 * even at its nominal {@link FieldState#REQUIRED} default.
 *
 * <p><b>{@code value} is one slot, and carries a parameter as readily as a literal.</b> Inside a template
 * body a token there is a parameter exactly when its text resolves into the enclosing entry's {@code
 * parameters} (§8.1's shadowing rule), and a closed entry has no parameters for one to resolve into -- so
 * the same slot is unambiguous at both ends and needs no label. A held body is not read as this vocabulary
 * until materialisation has substituted, which is what makes that true; §5.7's fixation (a parametric
 * {@code = P} sits in {@code REQUIRED} until its value is concrete, then becomes {@code REQUIRED_FIXED}) is
 * what the single channel costs and where it is paid.
 */
public record RecordField(String name, TypeRef type, FieldState state,
                           Optional<Token> value, Annotations annotations) {

    @Record
    public RecordField {
        annotations = annotations == null ? Annotations.empty() : annotations;
    }

    /** Same as the canonical constructor with no annotations -- every caller that has none to carry. */
    public RecordField(String name, TypeRef type, FieldState state, Optional<Token> value) {
        this(name, type, state, value, Annotations.empty());
    }

    /** A plain {@code REQUIRED} field with no default or fixed value. */
    public static RecordField required(String name, TypeRef type) {
        return new RecordField(name, type, FieldState.REQUIRED, Optional.empty());
    }

    /** A copy of this field with {@code annotations} replaced -- every other component unchanged. */
    public RecordField withAnnotations(Annotations annotations) {
        return new RecordField(name, type, state, value, annotations);
    }

    /** Excludes {@code annotations} -- metadata does not change a field's identity, as on {@code TypeDefinition}. */
    @Override
    public boolean equals(Object o) {
        return o instanceof RecordField other
                && Objects.equals(name, other.name)
                && Objects.equals(type, other.type)
                && state == other.state
                && Objects.equals(value, other.value);
    }

    /** Excludes {@code annotations} -- see {@link #equals}. */
    @Override
    public int hashCode() {
        return Objects.hash(name, type, state, value);
    }
}
