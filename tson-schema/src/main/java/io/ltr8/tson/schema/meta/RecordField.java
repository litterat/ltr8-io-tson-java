package io.ltr8.tson.schema.meta;

import io.ltr8.tson.base.SourcePosition;
import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Unbound;
import java.util.Objects;
import java.util.Optional;

/**
 * The kernel's {@code meta.record_field} record (Part 2 §5.2, §8.1): a field as the four facts a reader
 * consults. {@code optional} says the key may be omitted, {@code voidable} that a written {@code _} is
 * admitted, {@code role} what the schema's {@code value} is for, and {@code value} is that value. Each
 * reader decision reads its own: a written {@code _} reads {@code voidable}; a written value reads {@code
 * role} and {@code value}; a field never written reads {@code optional}, then {@code value} -- and nothing
 * stores what omission yields, which is derived from those two.
 *
 * <p><b>A field's name is not one of its facts.</b> It is the field's key in {@code record.fields}, an ordered
 * map ({@link RecordBody#fields}), so the name is said once, by the position that declares it.
 *
 * <p>Each fact is one mark of §5.2's spelling {@code name?: type? ~ value}: the name's {@code ?} is {@code
 * optional}, the type's is {@code voidable}, and {@code ~}/{@code =} give a {@link FieldRole#DEFAULT} or
 * {@link FieldRole#FIXED} value. Two combinations have no spelling: a FIXED voidable field, whose {@code _}
 * would be admitted where the pin refuses it, and a DEFAULT on a field that is not optional, a value only
 * omission reaches.
 *
 * <p><b>{@code type} is a {@link Reference}</b>: a type reference, or a sibling reference ({@code kind!}) naming the
 * field of the same record whose value chooses the type ([TSON-SCHEMA] §5.2, proposed) -- the one slot besides a
 * template field's that may hold one. {@link #typeRef()} is the type reference, for a consumer that needs one.
 *
 * <p><b>A record field is closed</b>: its {@code type} names no parameter and its {@code value} is a literal, never a
 * parameter. A field that may carry one is a {@code meta.record_template_field}, which only a record template holds,
 * and an application turns each of those into one of these.
 *
 * <p><b>A field never says it is a discriminator.</b> Which fields a sealed family dispatches on is the
 * enclosing record's own statement ({@code record.discriminators}), because §5.8 flattens a base's fields
 * into every member -- a per-field mark would have to be cleared again at each one, where a member's record
 * simply states none. What the linker checks of a named field -- required, not voidable, no group member, an
 * atom or enum type, no value of its own, and every subtype pinning it distinctly -- is §5.2's and §5.7's.
 *
 * <p><b>{@code position} is {@code @Unbound}</b>: §8.1's {@code meta.record_field} declares no such field, so nothing
 * fills it and the strict binding check would call it a mismatch. It is this implementation's own, kept for diagnostics
 * -- a read reporting against a field locates the pointer at the field ({@code /person/age}) and, without this, could
 * pair it only with the enclosing declaration's line, so six broken fields of one record all pointed at the record. It
 * is deliberately <em>not</em> carried in the annotation channel: that channel is schema data, resolves in the schema's
 * namespace (§6) and round-trips into resolver output, none of which is true of a source position.
 *
 * <p>Excluded from {@link #equals}/{@link #hashCode} on the same footing as {@code annotations}, and for
 * the same reason an entry's own position is kept apart from its value ({@code TsonSchema.EntryFacts}): the
 * resolver test suite compares hand-built expected values
 * against really-resolved ones, and a position in equality would stop two representations of one logical
 * field comparing equal.
 */
public record RecordField(Reference type, boolean optional, boolean voidable, FieldRole role,
                           Optional<Token> value, Annotations annotations,
                           @Unbound Optional<SourcePosition> position) {

    @Record
    public RecordField {
        annotations = annotations == null ? Annotations.empty() : annotations;
        position = position == null ? Optional.empty() : position;
    }

    /** Same as the canonical constructor with no position -- every caller that does not know its own source. */
    public RecordField(Reference type, boolean optional, boolean voidable, FieldRole role,
                        Optional<Token> value, Annotations annotations) {
        this(type, optional, voidable, role, value, annotations, Optional.empty());
    }

    /** Same as the canonical constructor with no annotations -- every caller that has none to carry. */
    public RecordField(Reference type, boolean optional, boolean voidable, FieldRole role,
                        Optional<Token> value) {
        this(type, optional, voidable, role, value, Annotations.empty(), Optional.empty());
    }

    /** {@code a: T} -- the key must be written, and {@code _} is refused. */
    public static RecordField required(Reference type) {
        return new RecordField(type, false, false, FieldRole.FREE, Optional.empty());
    }

    /** {@code a?: T} -- the key may be omitted, and {@code _} is refused. */
    public static RecordField optional(Reference type) {
        return new RecordField(type, true, false, FieldRole.FREE, Optional.empty());
    }

    /** {@code a: T?} -- the key must be written, and may be written {@code _}. */
    public static RecordField voidable(Reference type) {
        return new RecordField(type, false, true, FieldRole.FREE, Optional.empty());
    }

    /** {@code a?: T?} -- the key may be omitted, and may be written {@code _}. */
    public static RecordField optionalVoidable(Reference type) {
        return new RecordField(type, true, true, FieldRole.FREE, Optional.empty());
    }

    /** {@code a?: T ~ value} -- omission injects {@code value}, and a written value overrides it. */
    public static RecordField defaulted(Reference type, Token value) {
        return new RecordField(type, true, false, FieldRole.DEFAULT, Optional.of(value));
    }

    /** {@code a?: T = value} -- omission injects {@code value}, and a written value must equal it. */
    public static RecordField fixed(Reference type, Token value) {
        return new RecordField(type, true, false, FieldRole.FIXED, Optional.of(value));
    }

    /** {@code a: T = value} -- a marker: the key must be written, and its value must equal {@code value}. */
    public static RecordField marker(Reference type, Token value) {
        return new RecordField(type, false, false, FieldRole.FIXED, Optional.of(value));
    }

    /**
     * {@link #type} as the type it names. A sibling reference ({@code kind!}) is chosen per instance, and nothing that
     * asks a field for its type reads one yet: {@link #siblingNotImplemented}.
     */
    public TypeRef typeRef() {
        return typeRef(type);
    }

    /** A record field's or a record template field's {@code type} as the type it names -- see {@link #typeRef()}. */
    public static TypeRef typeRef(Reference type) {
        return switch (type) {
            case TypeRef reference -> reference;
            case SiblingRef sibling -> throw siblingNotImplemented(sibling);
        };
    }

    /**
     * The gap a consumer of a field's type reports for {@code sibling}: this library's, an {@link
     * UnsupportedOperationException}, which travels as {@code NOT_IMPLEMENTED}.
     */
    public static UnsupportedOperationException siblingNotImplemented(SiblingRef sibling) {
        return new UnsupportedOperationException("a sibling reference ('" + sibling.text() + "') at a record field's "
                + "type is not implemented yet");
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
        return omitted(optional, value.isPresent(), groupMember);
    }

    /** {@link #omitted(boolean)} over the facts it reads: whether the field is optional, and whether it has a value. */
    public static Omitted omitted(boolean optional, boolean valued, boolean groupMember) {
        if (!optional) {
            return Omitted.MISSING;
        }
        return valued && !groupMember ? Omitted.VALUE : Omitted.NOTHING;
    }

    /**
     * These facts in the words a diagnostic uses, by the spelling that produces them: {@code required},
     * {@code optional}, {@code defaulted} or {@code fixed}.
     */
    public String describe() {
        return describe(optional, role);
    }

    /** {@link #describe()} over the facts it reads. */
    public static String describe(boolean optional, FieldRole role) {
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
        return new RecordField(type, optional, voidable, role, value, annotations, position);
    }

    /**
     * A copy of this field with {@code type} replaced -- every other component unchanged.
     *
     * <p><b>Use this rather than the constructor wherever a field is rebuilt.</b> §8.3's use-site flattening
     * rewrites every field's type-ref, and a rebuild that names components positionally silently drops the
     * ones it does not mention -- {@code annotations} and {@code position} both, the second of which no test
     * comparing resolved values can catch, since it is excluded from equality.
     */
    public RecordField withType(Reference type) {
        return new RecordField(type, optional, voidable, role, value, annotations, position);
    }

    /** A copy of this field with its presence and role replaced -- every other component unchanged. */
    public RecordField withFacts(boolean optional, boolean voidable, FieldRole role) {
        return new RecordField(type, optional, voidable, role, value, annotations, position);
    }

    /** A copy of this field with {@code position} replaced -- every other component unchanged. */
    public RecordField withPosition(Optional<SourcePosition> position) {
        return new RecordField(type, optional, voidable, role, value, annotations, position);
    }

    /** Excludes {@code annotations}/{@code position} -- neither changes a field's identity. */
    @Override
    public boolean equals(Object o) {
        return o instanceof RecordField other
                && Objects.equals(type, other.type)
                && optional == other.optional
                && voidable == other.voidable
                && role == other.role
                && Objects.equals(value, other.value);
    }

    /** Excludes {@code annotations}/{@code position} -- see {@link #equals}. */
    @Override
    public int hashCode() {
        return Objects.hash(type, optional, voidable, role, value);
    }
}
