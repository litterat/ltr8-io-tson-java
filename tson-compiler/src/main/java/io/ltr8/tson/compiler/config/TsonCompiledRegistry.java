package io.ltr8.tson.compiler.config;

import io.ltr8.tson.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaLinker;
import io.ltr8.tson.schema.TsonSchemaRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A store of compiled meta-schemas ({@link TsonCompiledMetaSchema}), paired one-to-one with a
 * {@link TsonSchemaRegistry}'s own store of resolved, validated schemas.
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
    private final Map<String, TsonCompiledMetaSchema> compiled = new LinkedHashMap<>();

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
     * directly), compiles the *registered* result against {@code governingMeta} (never the raw
     * input -- a compiled reader needs linking already done), wraps the result as this schema's own
     * {@link TsonCompiledMetaSchema}, and stores it keyed by {@code schema}'s own {@code !!id}.
     * Returns the wrapped result, so the immediate next schema in a governing chain (which needs
     * *this* return value as its own {@code governingMeta} argument) doesn't have to call {@link
     * #get} right back.
     *
     * @param governingMeta the already-compiled meta-schema {@code schema.meta()} names -- meta-
     *                       kernel's own case aside (see {@link TsonCompiledMetaSchema#bootstrap}),
     *                       always a previous call's own return value
     */
    public synchronized TsonCompiledMetaSchema register(TsonSchema schema, TsonCompiledMetaSchema governingMeta) {
        TsonLinkedSchema linked = TsonSchemaLinker.link(schema, schemaRegistry);
        TsonLinkedSchema registered = schemaRegistry.register(linked);
        TsonCompiledSchema compiledSchema = TsonSchemaCompiler.compile(registered, governingMeta);
        TsonCompiledMetaSchema compiledMeta = new TsonCompiledMetaSchema(compiledSchema, resolver);
        compiled.put(TsonSchemaRegistry.canonicalIdentity(registered.schema().id()), compiledMeta);
        return compiledMeta;
    }

    /** {@code id} is matched by canonical identity ({@link TsonSchemaRegistry#canonicalIdentity}), so any spelling -- pinned or plain -- of a registered schema's own {@code !!id} finds it. */
    public synchronized Optional<TsonCompiledMetaSchema> get(String id) {
        return Optional.ofNullable(compiled.get(TsonSchemaRegistry.canonicalIdentity(id)));
    }
}
