package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.reader.*;
import io.ltr8.tson.compiler.reader.DeferredTypeReader;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Compiles a {@link TsonLinkedSchema} into a {@link TsonCompiledSchema} -- the "compile" stage of
 * this project's own parse -&gt; resolve -&gt; link -&gt; register -&gt; compile -&gt; read
 * pipeline vocabulary: this class is the verb, {@link TsonCompiledSchema} is the noun it produces.
 * Requires a {@link TsonLinkedSchema}, not a bare {@code TsonSchema} -- every {@code type_ref}
 * reachable from a body must already be argument-free (materialization already flattened any {@code
 * <...>} application into a reference to a synthesized entry), and every name a body refers to must
 * actually be present in {@code linkedSchema.schema().entries()}; a referenced-but-missing name is
 * treated as a bug, not a normal failure (see {@link Compilation#resolve}'s own {@code
 * IllegalStateException}).
 *
 * <p><b>Eager, not lazy.</b> {@link #compile} walks every one of {@code linkedSchema.schema()
 * .entries()} and resolves each before returning, so a caller that only ever reads a handful of a
 * large schema's own types still gets the same assurance that every other entry compiles too, and
 * any genuinely broken entry is discovered at compile time rather than piecemeal whenever some
 * future caller happens to {@code get} it.
 *
 * <p>A build failure for one specific entry doesn't abort the whole walk -- {@link
 * Compilation#resolve} catches it and substitutes an {@link ErrorReader}, so the schema as a whole
 * still compiles; only actually reading a value against that one entry fails, at that point. This
 * covers both a constructor with no registered {@link ValueReaderFactory} at all, and a factory
 * that's registered but rejects this particular entry.
 *
 * <p>Two compile modes share this eager walk, differing only in how a body's constructor name maps to
 * a factory. A <b>governed</b> compile ({@link #compile(TsonLinkedSchema, TsonCompiledMetaSchema)})
 * dispatches, scoped, through the governing meta-schema ({@link #governedFactory}): its own declared
 * vocabulary, then the global set only for a constructor the compiling schema declares itself,
 * otherwise rejected as out of scope. A <b>standalone</b> compile ({@link #compile(TsonLinkedSchema,
 * ValueReaderFactoryResolver)}) dispatches through a factory set directly, with no scoping -- for
 * reading an already-validated schema in a chosen mode. One non-factory case is checked first in
 * both: {@link Reference} (a bare {@code name => other_name} entry, §8.3) delegates straight to the
 * target's own reader, resolved recursively rather than built fresh.
 *
 * <p>The per-compilation mutable state (cycle-detection bookkeeping) lives in a private nested
 * {@link Compilation} helper, one instance per {@link #compile} call, discarded once it returns --
 * {@link TsonCompiledSchema} itself holds nothing but the finished, immutable result.
 */
public final class TsonSchemaCompiler {

    private TsonSchemaCompiler() {
    }

    /**
     * <b>Governed compile</b>: compiles {@code linkedSchema} against {@code governingMeta}, so each
     * body's constructor is dispatched, scoped, through that meta-schema (see {@link #governedFactory}).
     * This is what {@code TsonCompiledMetaRegistry} uses to compile a schema in the context of the meta that
     * governs it. {@code governingMeta} is always an already-compiled result from a previous compile --
     * meta-kernel's own case aside (see {@link TsonCompiledMetaSchema#bootstrap}).
     */
    public static TsonCompiledSchema compile(TsonLinkedSchema linkedSchema, TsonCompiledMetaSchema governingMeta) {
        TsonSchema schema = linkedSchema.schema();
        return compileWith(linkedSchema, name -> governedFactory(name, schema, governingMeta));
    }

    /**
     * Scoped factory dispatch for a governed compile: the governing meta-schema's own vocabulary first
     * ({@link TsonCompiledMetaSchema#constructor}); then, only if the schema being compiled declares the
     * constructor itself (a meta-schema introducing its own new constructor, e.g. meta.tn's {@code
     * float_type}), the global factory set; otherwise the constructor is out of scope. The resulting
     * {@link IllegalStateException} is caught per entry by {@link Compilation#resolve} and deferred into
     * an {@link ErrorReader}, same as any other unbuildable entry -- the schema still compiles, only
     * reading a value against that one entry fails.
     */
    private static ValueReaderFactory governedFactory(String constructorName, TsonSchema schema,
                                                      TsonCompiledMetaSchema governingMeta) {
        ValueReaderFactory inherited = governingMeta.constructor(constructorName);
        if (inherited != null) {
            return inherited;
        }
        TypeDefinition own = schema.entries().get(constructorName);
        if (own != null && own.constructor()) {
            return governingMeta.globalResolver().resolve(constructorName);
        }
        throw new IllegalStateException("constructor '" + constructorName + "' is out of scope: not in the "
                + "vocabulary of governing meta-schema '" + governingMeta.schema().id() + "', and not declared "
                + "by the schema being compiled");
    }

    /**
     * <b>Standalone compile</b>: compiles {@code linkedSchema} directly against a factory set, with no
     * governing meta-schema -- every body's constructor is dispatched through {@code factories} alone.
     * This is for reading data against an already-resolved, already-linked (hence already-validated)
     * schema in a chosen mode: the schema's own constructor usage was checked when it was registered,
     * so no governing-meta scoping is applied or needed here, and any of the mode's factories may be
     * used. It is also how meta-kernel itself is first compiled ({@link TsonCompiledMetaSchema#bootstrap}),
     * since meta-kernel declares its whole vocabulary itself and has no earlier meta to be governed by.
     */
    public static TsonCompiledSchema compile(TsonLinkedSchema linkedSchema, ValueReaderFactoryResolver factories) {
        return compileWith(linkedSchema, factories::resolve);
    }

    /**
     * The shared eager walk (see the "Eager, not lazy" note) -- {@code factoryFor} is the only thing that
     * differs between a governed and a standalone compile.
     */
    private static TsonCompiledSchema compileWith(
            TsonLinkedSchema linkedSchema, Function<String, ValueReaderFactory> factoryFor) {
        Compilation compilation = new Compilation(linkedSchema.schema(), factoryFor);
        for (String name : linkedSchema.schema().entries().keySet()) {
            compilation.resolve(name);
        }
        TsonCompiledSchema compiled = new TsonCompiledSchema(linkedSchema, Map.copyOf(compilation.finished));
        // Hand every reader that resolves at *read* time over to the finished schema, releasing this
        // compilation -- see CompiledReaders, and the "never escape" invariant below that this preserves.
        compilation.readers.bind(compiled);
        return compiled;
    }

    /**
     * One {@link #compile} call's own private, per-compilation mutable state and recursive build
     * logic -- {@code finished}/{@code building} never escape a single {@link #compile} invocation
     * (the finished map is copied into an immutable {@link TsonCompiledSchema} once compilation
     * completes; {@code building} is discarded entirely).
     *
     * <p>That invariant needs {@link CompiledReaders} to hold. A reader that resolves a name at <em>read</em>
     * time -- a dispatch reader choosing a variant, an annotation resolving the type it names -- has to keep
     * whatever it was handed for lookups, so handing it {@code this::resolve} directly would keep this whole
     * object, and both mutable collections, reachable for as long as any reader is. The handle is what every
     * reader gets instead, and rebinding it to the finished schema at the end of the walk is what actually
     * makes "never escape" true rather than aspirational.
     */
    private static final class Compilation {
        private final TsonSchema schema;
        private final Function<String, ValueReaderFactory> factoryFor;
        private final Map<String, TsonTypeReader<?>> finished = new LinkedHashMap<>();
        private final Set<String> building = new LinkedHashSet<>();

        /**
         * What every built reader is handed for its own name lookups. Resolves through this compilation
         * while the walk runs, then is rebound to the finished schema so nothing here outlives the call --
         * a reader that resolves at read time (dispatch, annotations) holds this, not {@code this}.
         */
        private final CompiledReaders readers = new CompiledReaders(this::resolve);

        Compilation(TsonSchema schema, Function<String, ValueReaderFactory> factoryFor) {
            this.schema = schema;
            this.factoryFor = factoryFor;
        }

        TsonTypeReader<?> resolve(String name) {
            TsonTypeReader<?> done = finished.get(name);
            if (done != null) {
                return done;
            }
            if (!building.add(name)) {
                return new DeferredTypeReader<>(name, finished);
            }
            try {
                TypeDefinition definition = schema.entries().get(name);
                if (definition == null) {
                    throw new IllegalStateException("'" + name + "' is referenced but not present in the schema -- "
                            + "TsonSchemaLinker should already have rejected this before compilation ever started");
                }
                TsonTypeReader<?> built;
                try {
                    built = build(name, definition);
                } catch (RuntimeException e) {
                    built = new ErrorReader(name, e);
                }
                finished.put(name, built);
                return built;
            } finally {
                building.remove(name);
            }
        }

        private TsonTypeReader<?> build(String name, TypeDefinition definition) {
            Top body = definition.body();
            if (body instanceof Reference r) {
                return resolve(r.target().name());
            }
            ValueReaderFactory factory = factoryFor.apply(TsonCompiledMetaSchema.typenameOf(body));
            return factory.create(name, definition, new ValueReaderContext(schema, readers));
        }
    }
}
