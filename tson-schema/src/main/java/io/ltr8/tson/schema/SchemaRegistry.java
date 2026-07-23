package io.ltr8.tson.schema;

import io.ltr8.tson.schema.registry.CanonicalIdentity;
import io.ltr8.tson.schema.registry.SchemaValidator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A store of resolved, validated schemas keyed by canonical identity ({@code [TSON-DATA] §2.2.1}),
 * mirroring Part 2 §10.1's "schema library" concept. {@link #register} runs the private pass-2
 * validation ({@code SchemaValidator}, in the internal-by-convention {@code
 * io.ltr8.tson.schema.registry} package -- see its own Javadoc for exactly what that checks) before
 * a schema is admitted; once admitted, a schema is never overwritten or removed -- together with
 * {@link TsonSchema#entries()} already being an unmodifiable map, this registration-time rejection
 * of re-registering the same identity *is* the "locked, no mutations allowed" guarantee.
 *
 * <p>Callers never need to compute a canonical identity themselves -- both {@link #register} (from
 * the schema's own {@code !!id}) and {@link #get} (from whatever raw URI a caller has, e.g. off a
 * document's {@code !!import} list) canonicalize internally.
 */
public final class SchemaRegistry {

    private final Map<String, TsonSchema> schemas = new LinkedHashMap<>();
    private final SchemaLoader loader;

    /** Default loader: resolves an import only if it's already registered -- nothing is ever fetched. */
    public SchemaRegistry() {
        this(null);
    }

    /**
     * @param loader consulted for a {@code !!import} target not already registered; {@code null}
     *               falls back to the registered-only default. Not yet consulted by {@link
     *               #register} -- {@code SchemaValidator} rejects a schema with any {@code !!import}
     *               outright today (see its own Javadoc) -- this constructor exists so that a
     *               caller building against this API now doesn't need to change call sites once
     *               import merging lands.
     */
    public SchemaRegistry(SchemaLoader loader) {
        this.loader = loader != null ? loader : this::lookupByCanonicalIdentity;
    }

    public synchronized TsonSchema register(TsonSchema schema) {
        String id = schema.id().orElseThrow(
                () -> new SchemaValidationException("schema has no !!id; cannot register"));
        String identity = CanonicalIdentity.of(id);
        if (schemas.containsKey(identity)) {
            throw new SchemaValidationException("a schema is already registered under '" + identity + "'");
        }
        TsonSchema validated = SchemaValidator.validate(schema, loader);
        schemas.put(identity, validated);
        return validated;
    }

    public synchronized Optional<TsonSchema> get(String uri) {
        return lookupByCanonicalIdentity(CanonicalIdentity.of(uri));
    }

    private synchronized Optional<TsonSchema> lookupByCanonicalIdentity(String canonicalIdentity) {
        return Optional.ofNullable(schemas.get(canonicalIdentity));
    }
}
