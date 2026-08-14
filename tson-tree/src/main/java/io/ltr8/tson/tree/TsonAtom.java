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
 * <p>The value is never {@code null} -- use {@link TsonNull} for the {@code null} token and {@link TsonAbsent}
 * for the {@code _} sentinel.
 */
public record TsonAtom(Object value, Optional<String> typeRef, List<TsonAnnotation> annotations)
        implements TsonValue {

    public TsonAtom {
        Objects.requireNonNull(value, "TsonAtom value must not be null -- use TsonNull or TsonAbsent");
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
}
