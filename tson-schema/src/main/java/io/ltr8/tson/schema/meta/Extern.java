package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.net.URI;
import java.util.List;

/**
 * meta.tn's {@code extern} constructor ({@code extern => ~sum & { schema: uri  types:
 * [type_name]? } }) -- a reference to a type (or the whole vocabulary) declared in a separate,
 * externally-governed schema, named by its own {@code !!id} rather than resolved through the
 * current schema's own namespace.
 *
 * <p><b>Implements {@link Sum}, not {@link Atom}</b> -- {@code extern} composes with {@code sum}
 * (the same base kind {@link UnknownType} uses), the second real {@code schema.meta} constructor
 * found outside the atom family.
 *
 * <p>{@code schema} is a real, declared {@code uri}-typed field (not a bare, schema-composed-
 * default string the way {@code Cidr4Type.spec}/{@code UriType.specification} are) -- the compiled
 * reader's own {@code uri} constructor produces a real {@link URI} for it, so this field is typed
 * {@link URI} directly, unlike those two. {@code types} is the schema's own {@code [type_name]?}
 * (an optional array of type names) -- modeled as a bare, always-present {@code List<String>}
 * (never {@code Optional<List<T>>}, which {@code tson-bind} doesn't support -- the same convention
 * {@code Cidr4Type.within}/{@code excluding} and {@code TypeDefinition.supertypes}/{@code
 * parameters} already follow), defensively defaulted to {@link List#of()} when absent.
 *
 * <p>Pure marker/constraint record, no parsing/validation behavior -- deliberately no {@code
 * tson-compiler} compiler exists for this atom yet (added as a {@code schema.meta}/{@link Sum} variant
 * only, so {@code !extern {...}}'s own resolution succeeds -- not to add real cross-schema
 * reference resolution).
 */
@Typename(name = "extern")
public record Extern(URI schema, List<String> types) implements Sum {

    public Extern {
        types = types != null ? List.copyOf(types) : List.of();
    }
}
