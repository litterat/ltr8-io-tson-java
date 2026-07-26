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
 *
 * <p><b>{@link #register} refuses any self-referential schema that isn't both genuinely
 * bootstrapped and materialized</b> (added 2026-07-26, on the user's own explicit direction; the
 * bootstrap-provenance half tightened the same day, also on explicit direction) -- {@code
 * selfReferential(schema) && !(schema.bootstrap() && schema.materialised())}, where {@code
 * selfReferential} is {@code schema.id().equals(schema.meta())} (Part 2 §1.5's "one deliberate
 * circularity," meta-kernel's own defining trait). Two distinct things are being checked, not one:
 * <i>shape</i> (is this schema self-referential at all -- computed here, inline, not exposed as a
 * {@link TsonSchema} method, since nothing else needs it) and <i>provenance</i> ({@link
 * TsonSchema#bootstrap()}, a real stored flag only {@code MetaKernelParser} ever sets to {@code
 * true} -- see its own Javadoc for why a derived check isn't enough: it can't tell "this really
 * came from reading meta-kernel.tn1 through the real two-pass bootstrap reader" apart from "this
 * merely happens to have matching {@code id}/{@code meta} fields," and the whole point of this
 * guard is to keep proving, continuously, that the real reader is what produces whatever ever gets
 * registered under meta-kernel's own identity). A self-referential schema that lacks the real
 * bootstrap provenance is refused outright, materialized or not -- it was never legitimately
 * produced in the first place. {@link #materializeBootstrap} is the one sanctioned way to turn the
 * raw, genuinely-bootstrapped form into a real, usable, materialized {@link TsonSchema} without
 * this rejection -- a caller that also needs it *persisted* under its own identity registers that
 * already-materialized result afterward, the ordinary way (still {@code bootstrap() == true}, now
 * also {@code materialised() == true}, so {@link #register} accepts it).
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

        if (selfReferential(schema) && schema.bootstrap()) {
            throw new SchemaValidationException("'" + schema.id() + "' is self-referential (its own "
                    + "!!meta names its own !!id) but wasn't produced by the real bootstrap reader and/or "
                    + "materialized -- use MetaKernelParser.getMetaKernelSchema() and "
                    + "SchemaRegistry.materializeBootstrap(TsonSchema) to materialize it without "
                    + "persisting an identity, then register that already-materialized result instead if "
                    + "it also needs to be found by !!import");
        }
        String identity = CanonicalIdentity.of(schema.id());
        if (schemas.containsKey(identity)) {
            throw new SchemaValidationException("a schema is already registered under '" + identity + "'");
        }
        TsonSchema validated = SchemaValidator.validate(schema, loader);
        schemas.put(identity, validated);
        return validated;
    }

    /** Part 2 §1.5's "one deliberate circularity": a schema whose own {@code !!meta} names its own {@code !!id}. */
    private static boolean selfReferential(TsonSchema schema) {
        return schema.id().equals(schema.meta());
    }

    /**
     * Materializes {@code bootstrap} -- meta-kernel's own raw, pre-loaded bootstrap output (see
     * {@link TsonSchema#bootstrap()}'s own Javadoc for why it can't be resolved the ordinary way) --
     * through the exact same validation/materialization pass {@link #register} runs (import
     * merging, argument-bearing {@code type_ref} synthesis, reference validation), but does
     * <b>not</b> store the result under a persistent identity in this registry, and never rejects it
     * the way {@link #register} now does. Meta-kernel's own {@code !!meta} names itself, so nothing
     * legitimately looks it up here by its own {@code !!id} via the ordinary resolution path anyway
     * -- this exists purely so a caller (e.g. building an object-binding-mode {@code
     * ParserFactoryRegistry}, which needs a genuinely materialized {@code TsonSchema} to validate
     * against up front) can get a usable result straight from the raw bootstrap object. A caller
     * that separately needs meta-kernel to actually be *findable* here (e.g. so some other schema's
     * own {@code !!import} of it can be merged) registers this method's own return value afterward,
     * via the ordinary {@link #register} -- now {@code materialised() == true}, so it's accepted
     * normally.
     *
     * @throws SchemaValidationException if {@code bootstrap.bootstrap()} is {@code false} -- this
     *                                    method exists specifically for the one self-referential
     *                                    schema, not as a general "materialize without persisting"
     *                                    escape hatch for ordinary schemas
     */
    public synchronized TsonSchema materializeBootstrap(TsonSchema bootstrap) {
        if (!bootstrap.bootstrap()) {
            throw new SchemaValidationException("'" + bootstrap.id() + "' was not produced by the real "
                    + "bootstrap reader (MetaKernelParser.getMetaKernelSchema()) -- "
                    + "SchemaRegistry.materializeBootstrap exists specifically for that case; use "
                    + "SchemaRegistry.register for an ordinary schema instead");
        }
        return SchemaValidator.validate(bootstrap, loader);
    }

    public synchronized Optional<TsonSchema> get(String uri) {
        return lookupByCanonicalIdentity(CanonicalIdentity.of(uri));
    }

    /**
     * Validates that {@code uri} is a well-formed canonical-identity candidate -- the same check
     * {@link #register}/{@link #get} already run internally on every {@code !!id}/lookup URI they
     * see, exposed on its own for a caller that wants to validate a candidate {@code !!id} up front
     * (e.g. before attempting to resolve a whole document that will eventually need one) without
     * triggering an actual lookup or registration. A thin wrapper, not a duplicate: {@link
     * CanonicalIdentity} stays internal-by-convention to this module (see that class's own Javadoc)
     * -- this is the sanctioned way for a caller outside it to run the same check.
     *
     * @throws SchemaValidationException if {@code uri} isn't a valid canonical-identity candidate
     */
    public static void validateIdentity(String uri) {
        CanonicalIdentity.of(uri);
    }

    private synchronized Optional<TsonSchema> lookupByCanonicalIdentity(String canonicalIdentity) {
        return Optional.ofNullable(schemas.get(canonicalIdentity));
    }
}
