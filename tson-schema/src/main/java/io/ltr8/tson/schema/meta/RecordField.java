package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Unbound;
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
 *
 * <p><b>{@code position} is {@code @Unbound}</b>, for the reason {@code TypeDefinition}'s own is: §8.1's
 * {@code record_field} declares no such field, so nothing fills it and the strict binding check would call
 * it a mismatch. It is this implementation's own, kept for diagnostics -- a read reporting against a field
 * locates the pointer at the field ({@code /person/age}) and, without this, could pair it only with the
 * enclosing declaration's line, so six broken fields of one record all pointed at the record. It is
 * deliberately <em>not</em> carried in the annotation channel: that channel is schema data, resolves one
 * hop against the governing meta (§6) and round-trips into resolver output, none of which is true of a
 * source position.
 *
 * <p>Excluded from {@link #equals}/{@link #hashCode} on the same footing as {@code annotations}, and for
 * {@code TypeDefinition}'s stated reason: the resolver test suite compares hand-built expected values
 * against really-resolved ones, and a position in equality would stop two representations of one logical
 * field comparing equal.
 */
public record RecordField(String name, TypeRef type, FieldState state,
                           Optional<Token> value, Annotations annotations,
                           @Unbound Optional<SourcePosition> position) {

    @Record
    public RecordField {
        annotations = annotations == null ? Annotations.empty() : annotations;
        position = position == null ? Optional.empty() : position;
    }

    /** Same as the canonical constructor with no position -- every caller that does not know its own source. */
    public RecordField(String name, TypeRef type, FieldState state, Optional<Token> value,
                        Annotations annotations) {
        this(name, type, state, value, annotations, Optional.empty());
    }

    /** Same as the canonical constructor with no annotations -- every caller that has none to carry. */
    public RecordField(String name, TypeRef type, FieldState state, Optional<Token> value) {
        this(name, type, state, value, Annotations.empty(), Optional.empty());
    }

    /** A plain {@code REQUIRED} field with no default or fixed value. */
    public static RecordField required(String name, TypeRef type) {
        return new RecordField(name, type, FieldState.REQUIRED, Optional.empty());
    }

    /** A copy of this field with {@code annotations} replaced -- every other component unchanged. */
    public RecordField withAnnotations(Annotations annotations) {
        return new RecordField(name, type, state, value, annotations, position);
    }

    /**
     * A copy of this field with {@code type} replaced -- every other component unchanged.
     *
     * <p><b>Use this rather than the constructor wherever a field is rebuilt.</b> §8.3's use-site flattening
     * rewrites every field's type-ref, and a rebuild that names components positionally silently drops the
     * ones it does not mention -- {@code annotations} and {@code position} both, the second of which no test
     * comparing resolved values can catch, since it is excluded from equality.
     */
    public RecordField withType(TypeRef type) {
        return new RecordField(name, type, state, value, annotations, position);
    }

    /** A copy of this field with {@code state} replaced -- every other component unchanged, as {@link #withType}. */
    public RecordField withState(FieldState state) {
        return new RecordField(name, type, state, value, annotations, position);
    }

    /** A copy of this field with {@code position} replaced -- every other component unchanged. */
    public RecordField withPosition(Optional<SourcePosition> position) {
        return new RecordField(name, type, state, value, annotations, position);
    }

    /** Excludes {@code annotations}/{@code position} -- neither changes a field's identity, as on {@code TypeDefinition}. */
    @Override
    public boolean equals(Object o) {
        return o instanceof RecordField other
                && Objects.equals(name, other.name)
                && Objects.equals(type, other.type)
                && state == other.state
                && Objects.equals(value, other.value);
    }

    /** Excludes {@code annotations}/{@code position} -- see {@link #equals}. */
    @Override
    public int hashCode() {
        return Objects.hash(name, type, state, value);
    }
}
