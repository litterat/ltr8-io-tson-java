package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaLinker;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A store of compiled schemas ({@link TsonCompiledSchema}), paired one-to-one with a {@link
 * TsonSchemaRegistry}'s own store of resolved, validated schemas -- the "also stored" half of the real
 * startup sequence: bootstrap meta-kernel, register it, *and* compile it and store the compiled
 * reader here, ready for reuse; then meta.tn1, then core.tn1, each the same way; then any
 * user-defined schema governed by one of them reuses whatever's already sitting in this registry
 * rather than recompiling its own governing chain from scratch.
 *
 * <p><b>One {@link TsonParserFactoryRegistry}, shared across every schema this registry compiles.</b>
 * Every schema meta-kernel/meta.tn1/core.tn1 govern is built from the same closed vocabulary of
 * constructors, so there's no reason for a caller to assemble a fresh registry per schema -- {@link
 * TsonParserFactoryRegistry#dom} is the obvious default, but a caller compiling in a different mode
 * (once one exists, see {@code TsonParserFactoryRegistry}'s own Javadoc) supplies its own instead.
 *
 * <p><b>Keyed by each schema's own raw {@code !!id} string, not a canonicalized identity.</b>
 * {@code io.ltr8.tson.schema.registry.CanonicalIdentity} is internal-by-convention to {@code
 * TsonSchemaRegistry} itself (see that package's own Javadoc) -- reaching into it from here, a
 * different module, would be exactly the kind of cross-module layering violation this project
 * otherwise avoids. In practice this is a non-issue for the one real use today (this registry's own
 * caller always registers and looks up using the exact same {@code !!id} string each schema
 * publishes), but it does mean two differently-spelled-but-equivalent URIs for the same schema
 * won't find each other here the way they would through {@link TsonSchemaRegistry#get} -- a real,
 * documented, narrower guarantee than {@code TsonSchemaRegistry}'s own, not an oversight.
 *
 * <p>Not thread-safe beyond {@code synchronized} on {@link #register}/{@link #get} themselves --
 * matches {@link TsonSchemaRegistry}'s own stated guarantee, no stronger.
 */
public final class TsonCompiledRegistry {

    private final TsonSchemaRegistry schemaRegistry;
    private final TsonParserFactoryRegistry factories;
    private final Map<String, TsonCompiledSchema> compiled = new LinkedHashMap<>();

    /** A fresh, empty {@link TsonSchemaRegistry} of its own -- the common case: this registry owns the whole registration+compilation pipeline for its caller. */
    public TsonCompiledRegistry(TsonParserFactoryRegistry factories) {
        this(new TsonSchemaRegistry(), factories);
    }

    /** @param schemaRegistry an existing registry to compile *alongside* -- a caller that already registers schemas elsewhere and wants compiled readers for them too, without this registry re-registering anything itself. */
    public TsonCompiledRegistry(TsonSchemaRegistry schemaRegistry, TsonParserFactoryRegistry factories) {
        this.schemaRegistry = schemaRegistry;
        this.factories = factories;
    }

    /** The paired {@link TsonSchemaRegistry} -- a caller resolving a declaration against one of the schemas registered here (structure namespace, {@code !!import}, ...) reads its resolved entries from there, same as always; this registry only adds the compiled half. */
    public TsonSchemaRegistry schemaRegistry() {
        return schemaRegistry;
    }

    /** The {@link TsonParserFactoryRegistry} every schema here is compiled with -- e.g. so a caller (such as {@code DefaultTsonCompiledSchemaLoader}'s own meta-kernel bootstrap case) can compile a one-off reader with the same factories, without registering or caching it here. */
    public TsonParserFactoryRegistry factories() {
        return factories;
    }

    /**
     * Links {@code schema} (via {@link TsonSchemaLinker#link}, using the paired {@link #schemaRegistry}
     * itself as the lookup source for {@code !!import}/{@code !!meta} targets) and registers the
     * result (via {@link TsonSchemaRegistry#register}, so the usual `!!import`-merging/reference-
     * validation rules all apply exactly as they would calling that directly), compiles the
     * *registered* result (never the raw input -- a compiled reader needs linking already done, per
     * {@link TsonCompiledSchema}'s own Javadoc), and stores it here keyed by {@code schema}'s own
     * {@code !!id}. Returns the compiled reader, so a caller with no further need to look it up
     * again later (e.g. the immediate next schema in a bootstrap chain, which needs *this* return
     * value as its own structure namespace's compiled reader) doesn't have to call {@link #get}
     * right back.
     */
    public synchronized TsonCompiledSchema register(TsonSchema schema) {
        TsonLinkedSchema linked = TsonSchemaLinker.link(schema, schemaRegistry);
        TsonLinkedSchema registered = schemaRegistry.register(linked);
        TsonCompiledSchema compiledParser = TsonSchemaCompiler.compile(registered.schema(), factories);
        compiled.put(registered.schema().id(), compiledParser);
        return compiledParser;
    }

    /** {@code id} must be the exact raw {@code !!id} string {@code schema} was registered with (see this class's own Javadoc on why -- unlike {@link TsonSchemaRegistry#get}, this is not canonicalized). */
    public synchronized Optional<TsonCompiledSchema> get(String id) {
        return Optional.ofNullable(compiled.get(id));
    }
}
