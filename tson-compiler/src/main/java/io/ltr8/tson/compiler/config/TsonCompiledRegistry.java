package io.ltr8.tson.compiler.config;

import io.ltr8.tson.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaLinker;
import io.ltr8.tson.schema.TsonSchemaRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A store of compiled schemas, paired one-to-one with a {@link TsonSchemaRegistry}'s own store of
 * resolved, validated schemas. A meta-layer schema (its own {@code !!meta} is meta-kernel) is stored
 * as the {@link TsonCompiledMetaSchema} subtype -- the only kind able to govern another schema's
 * compilation; every other schema is stored as a bare {@link TsonCompiledSchema}. {@link #getMeta}
 * narrows a lookup to the governing subtype, so a non-meta schema can never be handed back where a
 * governing meta is required.
 *
 * <p>Startup sequence this exists for: bootstrap meta-kernel ({@link
 * TsonCompiledMetaSchema#bootstrap}, outside this class entirely -- it needs no registry at all),
 * {@link #register} it (against its own bootstrap result, since nothing governs meta-kernel but
 * itself), then meta.tn1 (against meta-kernel's own freshly-registered {@link
 * TsonCompiledMetaSchema}), then core.tn1 (against meta.tn1's own) -- each step's own return value
 * is exactly what the next step needs as its {@code governingMeta} argument. Any user-defined schema
 * governed by one of these reuses whatever's already sitting in {@link #get} rather than
 * recompiling its own governing chain from scratch.
 *
 * <p><b>Keyed by canonical identity</b> ({@link TsonSchemaRegistry#canonicalIdentity}, scheme and query
 * stripped), matching the paired {@link TsonSchemaRegistry}. So two differently-spelled-but-equivalent
 * URIs for one schema -- in particular a hash-pinned {@code ?sha256=} reference and a plain one -- find
 * the same entry here, which is what lets a pinned reference resolve against an already-registered
 * schema without re-fetching or double-registering ([TSON-DATA] §2.2.1: the pin is verification
 * metadata, not identity).
 *
 * <p>Not thread-safe beyond {@code synchronized} on {@link #register}/{@link #get} themselves --
 * matches {@link TsonSchemaRegistry}'s own stated guarantee, no stronger.
 */
public final class TsonCompiledRegistry {

    private final TsonSchemaRegistry schemaRegistry;
    private final ValueReaderFactoryResolver resolver;
    private final Map<String, TsonCompiledSchema> compiled = new LinkedHashMap<>();

    /** A fresh, empty {@link TsonSchemaRegistry} of its own -- the common case: this registry owns the whole registration+compilation pipeline for its caller. */
    public TsonCompiledRegistry(ValueReaderFactoryResolver resolver) {
        this(new TsonSchemaRegistry(), resolver);
    }

    /** @param schemaRegistry an existing registry to compile *alongside* -- a caller that already registers schemas elsewhere and wants compiled readers for them too, without this registry re-registering anything itself. */
    public TsonCompiledRegistry(TsonSchemaRegistry schemaRegistry, ValueReaderFactoryResolver resolver) {
        this.schemaRegistry = schemaRegistry;
        this.resolver = resolver;
    }

    /** The paired {@link TsonSchemaRegistry} -- a caller resolving a declaration against one of the schemas registered here (structure namespace, {@code !!import}, ...) reads its resolved entries from there, same as always; this registry only adds the compiled half. */
    public TsonSchemaRegistry schemaRegistry() {
        return schemaRegistry;
    }

    /** The {@link ValueReaderFactoryResolver} every schema here is compiled with -- e.g. so a caller can compile a one-off reader (such as meta-kernel's own bootstrap, via {@link TsonCompiledMetaSchema#bootstrap}) with the same factories, without registering or caching it here. */
    public ValueReaderFactoryResolver resolver() {
        return resolver;
    }

    /**
     * Links {@code schema} (via {@link TsonSchemaLinker#link}, using the paired {@link
     * #schemaRegistry} itself as the lookup source for {@code !!import}/{@code !!meta} targets) and
     * registers the result (via {@link TsonSchemaRegistry#register}, so the usual {@code
     * !!import}-merging/reference-validation rules all apply exactly as they would calling that
     * directly), then compiles the *registered* result against {@code governingMeta} (never the raw
     * input -- a compiled reader needs linking already done) and stores it keyed by {@code schema}'s
     * own {@code !!id}.
     *
     * <p>A meta-layer schema (its own {@code !!meta} is meta-kernel) is wrapped as a {@link
     * TsonCompiledMetaSchema} so it can go on to govern others; every other schema is stored as the
     * bare {@link TsonCompiledSchema} it compiled to. The result is returned directly, so the
     * immediate next schema in a governing chain (which needs a governing meta -- reachable via {@link
     * #getMeta}) doesn't have to look it up again.
     *
     * @param governingMeta the already-compiled meta-schema {@code schema.meta()} names -- meta-
     *                       kernel's own case aside (see {@link TsonCompiledMetaSchema#bootstrap}),
     *                       always a previous call's own {@link #getMeta} result
     */
    public synchronized TsonCompiledSchema register(TsonSchema schema, TsonCompiledMetaSchema governingMeta) {
        TsonLinkedSchema linked = TsonSchemaLinker.link(schema, schemaRegistry);
        TsonLinkedSchema registered = schemaRegistry.register(linked);
        TsonCompiledSchema compiledSchema = TsonSchemaCompiler.compile(registered, governingMeta);
        TsonCompiledSchema result = isMetaLayer(registered.schema())
                ? new TsonCompiledMetaSchema(compiledSchema, resolver)
                : compiledSchema;
        compiled.put(TsonSchemaRegistry.canonicalIdentity(registered.schema().id()), result);
        return result;
    }

    /** {@code id} is matched by canonical identity ({@link TsonSchemaRegistry#canonicalIdentity}), so any spelling -- pinned or plain -- of a registered schema's own {@code !!id} finds it. */
    public synchronized Optional<TsonCompiledSchema> get(String id) {
        return Optional.ofNullable(compiled.get(TsonSchemaRegistry.canonicalIdentity(id)));
    }

    /**
     * As {@link #get}, narrowed to a governing meta-schema: present only when {@code id} names a registered
     * meta-layer schema. A non-meta schema is never handed back here, so it can't be passed where a governing
     * meta is required.
     */
    public synchronized Optional<TsonCompiledMetaSchema> getMeta(String id) {
        return get(id).filter(TsonCompiledMetaSchema.class::isInstance).map(TsonCompiledMetaSchema.class::cast);
    }

    /**
     * A meta-layer schema is one whose own {@code !!meta} names meta-kernel (§9's meta layer: meta-kernel and
     * meta). Only such a schema compiles to a {@link TsonCompiledMetaSchema} and may govern others.
     */
    private static boolean isMetaLayer(TsonSchema schema) {
        return TsonSchemaRegistry.canonicalIdentity(schema.meta())
                .equals(TsonSchemaRegistry.canonicalIdentity(TsonBundledSchemas.META_KERNEL_ID));
    }
}
