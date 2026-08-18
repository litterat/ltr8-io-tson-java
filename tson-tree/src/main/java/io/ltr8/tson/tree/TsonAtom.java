package io.ltr8.tson.tree;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A scalar leaf node holding a single resolved host value plus its optional type-ref -- one node for TSON's
 * whole atom vocabulary (§5), not a class per atom type. {@link #value()} is the host object a read produced:
 * a base-resolved {@code BigInteger}/{@code BigDecimal}/{@code Double}/{@code Boolean}/{@code String} for an
 * untyped or schemaless leaf, or an atom-narrowed {@code UUID}/{@code LocalDate}/{@code Integer}/… for a
 * built-in- or schema-typed one; {@link #typeRef()} names the TSON type when known (e.g. {@code "int32"}).
 * Typed access is via {@link #as(Class)} and the {@code asString}/{@code asBigInteger}/… conveniences.
 *
 * <p>The value is never {@code null} -- {@link TsonAbsent} is the node for a position holding no value.
 *
 * <p><b>{@link #toString()} renders the value alone</b>, not the record's own components, and that is
 * load-bearing rather than cosmetic: a reader reporting on a decoded value stringifies whatever it decoded,
 * and in tree mode that is one of these. The record default would put {@code
 * TsonAtom[value=a, typeRef=Optional[text], annotations=[]]} into a {@code Diagnostic}'s {@code
 * expected}/{@code actual} -- the two fields that exist so a consumer needn't parse the message -- and into
 * the message itself wherever a reader interpolates a value. The type-ref and annotations stay reachable
 * through the accessors; they are simply not what a value reads as. A {@code byte[]} value is the one shape
 * this does not improve ({@code String.valueOf} gives its identity hash either way) -- rendering binary is
 * {@code TsonTreeWriter}'s job, encoding and all.
 */
public record TsonAtom(Object value, Optional<String> typeRef, List<TsonAnnotation> annotations)
        implements TsonValue {

    public TsonAtom {
        Objects.requireNonNull(value, "TsonAtom value must not be null -- use TsonAbsent");
        annotations = List.copyOf(annotations);
    }

    public static TsonAtom of(Object value) {
        return new TsonAtom(value, Optional.empty(), List.of());
    }

    public static TsonAtom of(Object value, String typeRef) {
        return new TsonAtom(value, Optional.of(typeRef), List.of());
    }

    @Override
    public boolean isAtom() {
        return true;
    }

    @Override
    public <T> Optional<T> as(Class<T> type) {
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    /** The value alone -- see this class's own Javadoc for why the record's default is not what is wanted. */
    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
