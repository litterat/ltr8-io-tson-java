package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.Record;

import java.util.List;
import java.util.Objects;

/**
 * The meta-kernel's {@code type_ref} record (Part 2 §8.1): a resolved reference to a named entry
 * -- {@code name} is its only REQUIRED field, so an argument-free reference always writes in the
 * positional (bare-token) form, never {@code !type_ref { name: ... }} (§5.6's positional-form
 * rule, general over schema-backed data). {@code arguments}, when present, is a resolved {@code
 * type_argument} list; empty means "no {@code <...>}" was applied, i.e. a simple reference.
 *
 * <p>Same name as {@code tson-compiler}'s grammar-layer {@code io.ltr8.tson.compiler.ast.schema.TypeRef}
 * -- a different package, a deliberately different concept (source-text reference vs. resolved
 * reference), matching the kernel's own choice to call both "type_ref" too.
 *
 * <p>{@code arguments} is bound from {@code type_ref}'s OPTIONAL {@code arguments: [type_argument]?}
 * field, so an absent value arrives as {@code null} -- which, per the "empty means no {@code <...>}"
 * rule above, is the same thing as no arguments. The constructor therefore normalizes {@code null} to
 * the empty list rather than rejecting it: absent and empty are one state for a reference, and there is
 * no wire form for "present but empty" arguments to keep distinct.
 *
 * <p><b>{@code annotations} carries the wire annotations written on the reference itself</b>, which is a
 * type-ref's own channel rather than the enclosing field's: [TSON-SCHEMA] §8.3 attaches {@code
 * @alias:name} to the <em>type value</em> when a use site is flattened past a {@code REFERENCE} entry, so
 * {@code type: @alias:field_name token} records both where the reference now points and what the author
 * wrote. Without somewhere on this record to keep it, a read discards it and a write cannot produce it.
 * Excluded from {@link #equals} for the same reason {@code RecordField} excludes its own: an alias is
 * metadata about where a reference came from, and §8.2 keys entry identity on where it points.
 */
public record TypeRef(String name, List<TypeArgument> arguments, Annotations annotations) {

    @Record
    public TypeRef {
        Objects.requireNonNull(name, "name");
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        annotations = annotations == null ? Annotations.empty() : annotations;
    }

    /** Same as the canonical constructor with no annotations -- every caller that has none to carry. */
    public TypeRef(String name, List<TypeArgument> arguments) {
        this(name, arguments, Annotations.empty());
    }

    /** A bare reference with no type arguments, e.g. a plain field type like {@code integer}. */
    public static TypeRef of(String name) {
        return new TypeRef(name, null);
    }

    /** A copy of this reference with {@code annotations} replaced -- every other component unchanged. */
    public TypeRef withAnnotations(Annotations annotations) {
        return new TypeRef(name, arguments, annotations);
    }

    /** Excludes {@code annotations} -- metadata does not change a reference's identity, as on {@code RecordField}. */
    @Override
    public boolean equals(Object o) {
        return o instanceof TypeRef other
                && Objects.equals(name, other.name)
                && Objects.equals(arguments, other.arguments);
    }

    /** Excludes {@code annotations} -- see {@link #equals}. */
    @Override
    public int hashCode() {
        return Objects.hash(name, arguments);
    }
}
