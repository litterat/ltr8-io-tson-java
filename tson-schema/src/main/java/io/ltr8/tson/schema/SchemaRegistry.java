package io.ltr8.tson.schema;

import io.ltr8.tson.schema.registry.CanonicalIdentity;
import io.ltr8.tson.schema.registry.SchemaLinker;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A store of linked schemas keyed by canonical identity ({@code [TSON-DATA] §2.2.1}), mirroring
 * Part 2 §10.1's "schema library" concept. {@link #register} only accepts a {@link
 * LinkedTsonSchema} -- proof, at the type level, that {@link SchemaLinker#link} already ran (import
 * merging, argument-bearing {@code type_ref} synthesis, reference validation) -- and does nothing
 * but store it; once admitted, a schema is never overwritten or removed -- together with {@link
 * TsonSchema#entries()} already being an unmodifiable map, this registration-time rejection of
 * re-registering the same identity *is* the "locked, no mutations allowed" guarantee.
 *
 * <p><b>Linking and storing used to be one operation, done together inside {@code register}</b>
 * (a plain {@code TsonSchema} in, a runtime {@code materialised} flag flipped on the way out) --
 * split apart 2026-07-27, on the user's own explicit direction, borrowing standard compiler
 * vocabulary for the pipeline as a whole (parse -&gt; resolve -&gt; link -&gt; register -&gt;
 * compile -&gt; read): a caller now calls {@link SchemaLinker#link} explicitly (passing this
 * registry itself as the {@link SchemaLoader} it needs for {@code !!import}/{@code !!meta}
 * lookups -- see {@code implements SchemaLoader} below) and only then calls {@link #register}. Two
 * consequences: {@code register} can no longer silently do the wrong thing with an unlinked
 * schema (there's no overload that accepts one), and "has this been linked" is answered by the
 * compiler, not by a flag every caller has to remember to check.
 *
 * <p>Implements {@link SchemaLoader} itself (a thin delegation to {@link #get}) purely so a caller
 * can write {@code SchemaLinker.link(schema, registry)} directly, passing this registry as its own
 * lookup source, without a separate method reference.
 *
 * <p>Callers never need to compute a canonical identity themselves -- both {@link #register} (from
 * the schema's own {@code !!id}) and {@link #get} (from whatever raw URI a caller has, e.g. off a
 * document's {@code !!import} list) canonicalize internally.
 *
 * <p><b>{@link #register} refuses any self-referential schema (its own {@code !!meta} names its
 * own {@code !!id}) whose {@link TsonSchema#bootstrap()} is {@code true}</b> -- meta-kernel's own
 * defining trait (Part 2 §1.5's "one deliberate circularity"), and the only schema ever allowed to
 * have that shape. {@code bootstrap()} is a real, stored flag that only {@code
 * MetaKernelParser.getMetaKernelSchema()} ever sets, so this guard keeps proving, continuously,
 * that meta-kernel's own identity can only ever be registered by something that genuinely came
 * from the real bootstrap reader -- not just something shaped like it. {@link #linkBootstrap} is
 * the one sanctioned way to turn the raw bootstrap form into a {@link LinkedTsonSchema} without
 * this rejection -- a caller that also needs it *persisted* under its own identity still can't
 * register that result directly (it's still {@code bootstrap() == true}); the one way meta-kernel's
 * own identity can actually be registered is resolving its document a second time, ordinarily
 * (never setting {@code bootstrap}), against a coordinator seeded from the one-off linked
 * bootstrap result.
 */
public final class SchemaRegistry implements SchemaLoader {

    private final Map<String, LinkedTsonSchema> schemas = new LinkedHashMap<>();
    private final SchemaLoader loader;

    /** Default loader: resolves an import only if it's already registered -- nothing is ever fetched. */
    public SchemaRegistry() {
        this(null);
    }

    /**
     * @param loader consulted for a {@code !!import}/{@code !!meta} target not already registered;
     *               {@code null} falls back to the registered-only default (this registry itself).
     */
    public SchemaRegistry(SchemaLoader loader) {
        this.loader = loader != null ? loader : this::lookupByCanonicalIdentity;
    }

    public synchronized LinkedTsonSchema register(LinkedTsonSchema schema) {
        TsonSchema unwrapped = schema.schema();
        if (selfReferential(unwrapped) && unwrapped.bootstrap()) {
            throw new SchemaValidationException("'" + unwrapped.id() + "' is self-referential (its own "
                    + "!!meta names its own !!id) and bootstrap() == true -- meta-kernel's own identity "
                    + "must be registered via a schema resolved ordinarily (SchemaResolver.resolveAll,"
                    + " which never sets bootstrap), never the bootstrap-produced form directly, "
                    + "materialized or not");
        }
        String identity = CanonicalIdentity.of(unwrapped.id());
        if (schemas.containsKey(identity)) {
            throw new SchemaValidationException("a schema is already registered under '" + identity + "'");
        }
        schemas.put(identity, schema);
        return schema;
    }

    /** Part 2 §1.5's "one deliberate circularity": a schema whose own {@code !!meta} names its own {@code !!id}. */
    private static boolean selfReferential(TsonSchema schema) {
        return schema.id().equals(schema.meta());
    }

    /**
     * Links {@code bootstrap} -- meta-kernel's own raw, pre-loaded bootstrap output (see {@link
     * TsonSchema#bootstrap()}'s own Javadoc for why it can't be resolved the ordinary way) -- via
     * {@link SchemaLinker#link}, but does <b>not</b> store the result under a persistent identity
     * in this registry, and {@link #register} refuses it outright regardless (see this class's own
     * Javadoc). Exists purely so a caller (e.g. building an object-binding-mode {@code
     * ParserFactoryRegistry}, which needs a genuinely linked {@code TsonSchema} to validate against
     * up front) can get a usable result straight from the raw bootstrap object, without separately
     * wiring a {@link SchemaLoader}.
     *
     * @throws SchemaValidationException if {@code bootstrap.bootstrap()} is {@code false} -- this
     *                                    method exists specifically for the one self-referential
     *                                    schema, not as a general "link without registering" escape
     *                                    hatch for ordinary schemas (call {@link SchemaLinker#link}
     *                                    directly for that)
     */
    public synchronized LinkedTsonSchema linkBootstrap(TsonSchema bootstrap) {
        if (!bootstrap.bootstrap()) {
            throw new SchemaValidationException("'" + bootstrap.id() + "' was not produced by the real "
                    + "bootstrap reader (MetaKernelParser.getMetaKernelSchema()) -- "
                    + "SchemaRegistry.linkBootstrap exists specifically for that case; call "
                    + "SchemaLinker.link directly for an ordinary schema instead");
        }
        return SchemaLinker.link(bootstrap, loader);
    }

    @Override
    public synchronized Optional<LinkedTsonSchema> load(String canonicalIdentity) {
        return lookupByCanonicalIdentity(canonicalIdentity);
    }

    public synchronized Optional<LinkedTsonSchema> get(String uri) {
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

    private synchronized Optional<LinkedTsonSchema> lookupByCanonicalIdentity(String canonicalIdentity) {
        return Optional.ofNullable(schemas.get(canonicalIdentity));
    }
}
