package io.ltr8.tson.schema.meta;

import io.ltr8.tson.base.SourcePosition;
import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Unbound;
import java.util.Objects;
import java.util.Optional;

/**
 * The meta-kernel's {@code record_field} record (Part 2 §5.2, §8.1): a field as the four facts a reader
 * consults. {@code optional} says the key may be omitted, {@code voidable} that a written {@code _} is
 * admitted, {@code role} what the schema's {@code value} is for, and {@code value} is that value. Each
 * reader decision reads its own: a written {@code _} reads {@code voidable}; a written value reads {@code
 * role} and {@code value}; a field never written reads {@code optional}, then {@code value} -- and nothing
 * stores what omission yields, which is derived from those two.
 *
 * <p>Each fact is one mark of §5.2's spelling {@code name?: type? ~ value}: the name's {@code ?} is {@code
 * optional}, the type's is {@code voidable}, and {@code ~}/{@code =} give a {@link FieldRole#DEFAULT} or
 * {@link FieldRole#FIXED} value. Two combinations have no spelling: a FIXED voidable field, whose {@code _}
 * would be admitted where the pin refuses it, and a DEFAULT on a field that is not optional, a value only
 * omission reaches.
 *
 * <p><b>{@code value} is one slot, and carries a parameter as readily as a literal.</b> Inside a template
 * body a token there is a parameter exactly when its text resolves into the enclosing entry's {@code
 * parameters} (§8.1's shadowing rule), and a closed entry has no parameters for one to resolve into -- so
 * the same slot is unambiguous at both ends and needs no label. A held body is not read as this vocabulary
 * until materialisation has substituted, which is what makes that true; §5.7's fixation (a parametric
 * {@code = P} is a required {@link FieldRole#FREE} field carrying the parameter until its value is concrete,
 * then becomes optional and FIXED) is what the single channel costs and where it is paid. That held state
 * is the one place a FREE field carries a value.
 *
 * <p><b>A field never says it is a discriminator.</b> Which fields a sealed family dispatches on is the
 * enclosing record's own statement ({@code record.discriminators}), because §5.8 flattens a base's fields
 * into every member -- a per-field mark would have to be cleared again at each one, where a member's record
 * simply states none. What the linker checks of a named field -- required, not voidable, no group member, an
 * atom or enum type, no value of its own, and every subtype pinning it distinctly -- is §5.2's and §5.7's.
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
public record RecordField(String name, TypeRef type, boolean optional, boolean voidable, FieldRole role,
                           Optional<Token> value, Annotations annotations,
                           @Unbound Optional<SourcePosition> position) {

    @Record
    public RecordField {
        annotations = annotations == null ? Annotations.empty() : annotations;
        position = position == null ? Optional.empty() : position;
    }

    /** Same as the canonical constructor with no position -- every caller that does not know its own source. */
    public RecordField(String name, TypeRef type, boolean optional, boolean voidable, FieldRole role,
                        Optional<Token> value, Annotations annotations) {
        this(name, type, optional, voidable, role, value, annotations, Optional.empty());
    }

    /** Same as the canonical constructor with no annotations -- every caller that has none to carry. */
    public RecordField(String name, TypeRef type, boolean optional, boolean voidable, FieldRole role,
                        Optional<Token> value) {
        this(name, type, optional, voidable, role, value, Annotations.empty(), Optional.empty());
    }

    /** {@code a: T} -- the key must be written, and {@code _} is refused. */
    public static RecordField required(String name, TypeRef type) {
        return new RecordField(name, type, false, false, FieldRole.FREE, Optional.empty());
    }

    /** {@code a?: T} -- the key may be omitted, and {@code _} is refused. */
    public static RecordField optional(String name, TypeRef type) {
        return new RecordField(name, type, true, false, FieldRole.FREE, Optional.empty());
    }

    /** {@code a: T?} -- the key must be written, and may be written {@code _}. */
    public static RecordField voidable(String name, TypeRef type) {
        return new RecordField(name, type, false, true, FieldRole.FREE, Optional.empty());
    }

    /** {@code a?: T?} -- the key may be omitted, and may be written {@code _}. */
    public static RecordField optionalVoidable(String name, TypeRef type) {
        return new RecordField(name, type, true, true, FieldRole.FREE, Optional.empty());
    }

    /** {@code a?: T ~ value} -- omission injects {@code value}, and a written value overrides it. */
    public static RecordField defaulted(String name, TypeRef type, Token value) {
        return new RecordField(name, type, true, false, FieldRole.DEFAULT, Optional.of(value));
    }

    /** {@code a?: T = value} -- omission injects {@code value}, and a written value must equal it. */
    public static RecordField fixed(String name, TypeRef type, Token value) {
        return new RecordField(name, type, true, false, FieldRole.FIXED, Optional.of(value));
    }

    /** {@code a: T = value} -- a marker: the key must be written, and its value must equal {@code value}. */
    public static RecordField marker(String name, TypeRef type, Token value) {
        return new RecordField(name, type, false, false, FieldRole.FIXED, Optional.of(value));
    }

    /** What a field yields when a document never writes it -- see {@link #omitted}. */
    public enum Omitted { MISSING, VALUE, NOTHING }

    /**
     * What this field yields when a document never writes it: the one reader decision no single fact answers,
     * derived here once so every reader derives it alike, and why nothing stores it. A field that is not
     * optional is the missing-field error; an optional one with a value injects it, whatever its role, unless
     * it is a field group's member -- a member's presence selects the group's alternative, so a member is
     * never supplied; anything else stays absent.
     */
    public Omitted omitted(boolean groupMember) {
        if (!optional) {
            return Omitted.MISSING;
        }
        return value.isPresent() && !groupMember ? Omitted.VALUE : Omitted.NOTHING;
    }

    /**
     * These facts in the words a diagnostic uses, by the spelling that produces them: {@code required},
     * {@code optional}, {@code defaulted} or {@code fixed}.
     */
    public String describe() {
        if (!optional) {
            return "required";
        }
        return switch (role) {
            case FREE -> "optional";
            case DEFAULT -> "defaulted";
            case FIXED -> "fixed";
        };
    }

    /** A copy of this field with {@code annotations} replaced -- every other component unchanged. */
    public RecordField withAnnotations(Annotations annotations) {
        return new RecordField(name, type, optional, voidable, role, value, annotations, position);
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
        return new RecordField(name, type, optional, voidable, role, value, annotations, position);
    }

    /** A copy of this field with its presence and role replaced -- every other component unchanged. */
    public RecordField withFacts(boolean optional, boolean voidable, FieldRole role) {
        return new RecordField(name, type, optional, voidable, role, value, annotations, position);
    }

    /** A copy of this field with {@code position} replaced -- every other component unchanged. */
    public RecordField withPosition(Optional<SourcePosition> position) {
        return new RecordField(name, type, optional, voidable, role, value, annotations, position);
    }

    /** Excludes {@code annotations}/{@code position} -- neither changes a field's identity, as on {@code TypeDefinition}. */
    @Override
    public boolean equals(Object o) {
        return o instanceof RecordField other
                && Objects.equals(name, other.name)
                && Objects.equals(type, other.type)
                && optional == other.optional
                && voidable == other.voidable
                && role == other.role
                && Objects.equals(value, other.value);
    }

    /** Excludes {@code annotations}/{@code position} -- see {@link #equals}. */
    @Override
    public int hashCode() {
        return Objects.hash(name, type, optional, voidable, role, value);
    }
}
