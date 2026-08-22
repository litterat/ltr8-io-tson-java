package io.ltr8.tson.schema;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A store of linked schemas keyed by canonical identity ({@code [TSON-DATA] §2.2.1}), mirroring
 * Part 2 §10.1's "schema library" concept. {@link #register} only accepts a {@link
 * TsonLinkedSchema} -- proof, at the type level, that {@code TsonSchemaLinker.link} already ran (import
 * merging, argument-bearing {@code type_ref} synthesis, reference validation) -- and does nothing
 * but store it; once admitted, a schema is never overwritten or removed -- together with {@link
 * TsonSchema#entries()} already being an unmodifiable map, this registration-time rejection of
 * re-registering the same identity *is* the "locked, no mutations allowed" guarantee.
 *
 * <p><b>Linking and storing are two operations, not one</b>, following the pipeline's own compiler
 * vocabulary (parse -&gt; resolve -&gt; link -&gt; register -&gt; compile -&gt; read): a caller calls
 * {@code TsonSchemaLinker.link} explicitly (passing this registry itself as the {@link TsonSchemaLoader}
 * it needs for {@code !!import}/{@code !!meta} lookups -- see {@code implements TsonSchemaLoader} below)
 * and only then calls {@link #register}. Two consequences: {@code register} cannot silently do the wrong
 * thing with an unlinked schema (there's no overload that accepts one), and "has this been linked" is
 * answered by the compiler, not by a flag every caller has to remember to check.
 *
 * <p>Implements {@link TsonSchemaLoader} itself (a thin delegation to {@link #get}) purely so a caller
 * can write {@code TsonSchemaLinker.link(schema, registry)} directly, passing this registry as its own
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
 * MetaKernelBootstrapResolver.getMetaKernelSchema()} ever sets, so this guard keeps proving, continuously,
 * that meta-kernel's own identity can only ever be registered by something that genuinely came
 * from the real bootstrap reader -- not just something shaped like it. {@code
 * TsonSchemaLinker.linkBootstrap} is the one sanctioned way to turn the raw bootstrap form into a
 * {@link TsonLinkedSchema} without this rejection -- it lives there because it's a linking operation,
 * not a storage one, and this registry never stores its own result anyway
 * (a caller that also needs it *persisted* under its own identity still can't register that result
 * directly -- it's still {@code bootstrap() == true}); the one way meta-kernel's own identity can
 * actually be registered is resolving its document a second time, ordinarily (never setting {@code
 * bootstrap}), against a coordinator seeded from the one-off linked bootstrap result.
 */
public final class TsonSchemaRegistry implements TsonSchemaLoader {

    private final Map<String, TsonLinkedSchema> schemas = new LinkedHashMap<>();
    private final TsonSchemaLoader loader;

    /** Default loader: resolves an import only if it's already registered -- nothing is ever fetched. */
    public TsonSchemaRegistry() {
        this(null);
    }

    /**
     * @param loader consulted for a {@code !!import}/{@code !!meta} target not already registered;
     *               {@code null} falls back to the registered-only default (this registry itself).
     */
    public TsonSchemaRegistry(TsonSchemaLoader loader) {
        this.loader = loader != null ? loader : this::lookupByCanonicalIdentity;
    }

    public synchronized TsonLinkedSchema register(TsonLinkedSchema schema) {
        String identity = checkRegistrable(schema);
        if (schemas.containsKey(identity)) {
            throw new TsonSchemaValidationException("a schema is already registered under '" + identity + "'");
        }
        schemas.put(identity, schema);
        return schema;
    }

    /**
     * Registers {@code schema} if its identity is free, and otherwise returns the schema already registered
     * under it -- {@link #register} for a caller whose own contract is <em>idempotent</em> resolution rather
     * than "register this, and fail if it is already there".
     *
     * <p><b>Why this exists as a second method rather than softening the first.</b> The on-demand resolve
     * path (a data document naming a schema this registry has not seen) is a lookup that fills the cache on
     * a miss, so two threads reaching the miss together both resolve the same document from the same source
     * and both arrive here with equivalent results. Under {@link #register} the loser gets "a schema is
     * already registered under ...", which on a read surfaces as a {@code SCHEMA_ERROR} against a document
     * that has nothing wrong with it. Taking the winner's entry is the answer there, and everything
     * downstream keys on identity, so which of two equivalent linked forms wins does not matter.
     *
     * <p>{@link #register} stays strict, because registering the same identity twice <em>explicitly</em> is
     * still an error and "no overwrite" is half of what makes a registry locked. Nothing here overwrites
     * either: the first entry for an identity is the only one that ever exists.
     */
    public synchronized TsonLinkedSchema registerIfAbsent(TsonLinkedSchema schema) {
        String identity = checkRegistrable(schema);
        TsonLinkedSchema existing = schemas.get(identity);
        if (existing != null) {
            return existing;
        }
        schemas.put(identity, schema);
        return schema;
    }

    /** The validation both registration paths share; returns the canonical identity to key on. */
    private static String checkRegistrable(TsonLinkedSchema schema) {
        TsonSchema unwrapped = schema.schema();
        if (selfReferential(unwrapped) && unwrapped.bootstrap()) {
            throw new TsonSchemaValidationException("'" + unwrapped.id() + "' is self-referential (its own "
                    + "!!meta names its own !!id) and bootstrap() == true -- meta-kernel's own identity "
                    + "must be registered via a schema resolved ordinarily (TsonSchemaResolver.resolveSchema,"
                    + " which never sets bootstrap), never the bootstrap-produced form directly, "
                    + "materialized or not");
        }
        return TsonCanonicalIdentity.canonicalize(unwrapped.id());
    }

    /**
     * Part 2 §1.5's "one deliberate circularity": a schema whose own {@code !!meta} names its own
     * {@code !!id}. Compared by canonical identity, not raw string -- meta-kernel's own {@code !!id}
     * may carry a {@code ?sha256=} pin while its self-{@code !!meta} cannot (pinning the self-reference
     * would be circular, §2.2.1), so the two differ as strings but name the same identity.
     */
    private static boolean selfReferential(TsonSchema schema) {
        return TsonCanonicalIdentity.sameIdentity(schema.id(), schema.meta());
    }

    @Override
    public synchronized Optional<TsonLinkedSchema> load(String canonicalIdentity) {
        return lookupByCanonicalIdentity(canonicalIdentity);
    }

    public synchronized Optional<TsonLinkedSchema> get(String uri) {
        return lookupByCanonicalIdentity(TsonCanonicalIdentity.canonicalize(uri));
    }

    private synchronized Optional<TsonLinkedSchema> lookupByCanonicalIdentity(String canonicalIdentity) {
        return Optional.ofNullable(schemas.get(canonicalIdentity));
    }
}
