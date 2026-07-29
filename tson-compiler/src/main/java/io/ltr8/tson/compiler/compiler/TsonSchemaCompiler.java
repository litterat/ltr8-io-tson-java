package io.ltr8.tson.compiler.compiler;

import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Compiles a {@link TsonLinkedSchema} into a {@link TsonCompiledSchema} -- the "compile" stage of
 * this project's own parse -&gt; resolve -&gt; link -&gt; register -&gt; compile -&gt; read pipeline
 * vocabulary: this class is the verb, {@link TsonCompiledSchema} is the noun it produces. Requires a
 * {@link TsonLinkedSchema}, not a bare {@code TsonSchema} -- every {@code type_ref} reachable from a
 * body must already be argument-free (materialization already flattened any {@code <...>}
 * application into a reference to a synthesized entry), and every name a body refers to must
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
 * <p>Dispatch to a factory is uniform across every constructor -- atom and composite alike --
 * entirely driven by {@code metaSchema}, the governing {@link TsonCompiledMetaSchema} {@link
 * #compile} was given, keyed by the resolved body's own constructor name (see {@link
 * TsonCompiledMetaSchema#create}). One non-factory case is checked first: {@link Reference} (a bare
 * {@code name => other_name} entry, §8.3) delegates straight to the target's own reader, resolved
 * recursively rather than built fresh.
 *
 * <p>The per-compilation mutable state (cycle-detection bookkeeping) lives in a private nested {@link
 * Compilation} helper, one instance per {@link #compile} call, discarded once it returns --
 * {@link TsonCompiledSchema} itself holds nothing but the finished, immutable result.
 */
public final class TsonSchemaCompiler {

    private TsonSchemaCompiler() {
    }

    /**
     * Compiles {@code linkedSchema} against {@code metaSchema}, eagerly -- see this class's own
     * "Eager, not lazy" note. {@code metaSchema} is the governing meta-schema for this compile --
     * meta-kernel's own case aside (see {@link TsonCompiledMetaSchema#bootstrap}), always an
     * already-compiled result from a previous {@link #compile} call.
     */
    public static TsonCompiledSchema compile(TsonLinkedSchema linkedSchema, TsonCompiledMetaSchema metaSchema) {
        Compilation compilation = new Compilation(linkedSchema.schema(), metaSchema);
        for (String name : linkedSchema.schema().entries().keySet()) {
            compilation.resolve(name);
        }
        return new TsonCompiledSchema(linkedSchema, Map.copyOf(compilation.finished));
    }

    /**
     * One {@link #compile} call's own private, per-compilation mutable state and recursive build
     * logic -- {@code finished}/{@code building} never escape a single {@link #compile} invocation
     * (the finished map is copied into an immutable {@link TsonCompiledSchema} once compilation
     * completes; {@code building} is discarded entirely).
     */
    private static final class Compilation {
        private final TsonSchema schema;
        private final TsonCompiledMetaSchema metaSchema;
        private final Map<String, TsonValueReader<?>> finished = new LinkedHashMap<>();
        private final Set<String> building = new LinkedHashSet<>();

        Compilation(TsonSchema schema, TsonCompiledMetaSchema metaSchema) {
            this.schema = schema;
            this.metaSchema = metaSchema;
        }

        TsonValueReader<?> resolve(String name) {
            TsonValueReader<?> done = finished.get(name);
            if (done != null) {
                return done;
            }
            if (!building.add(name)) {
                return new DeferredValueReader<>(name, finished);
            }
            try {
                TypeDefinition definition = schema.entries().get(name);
                if (definition == null) {
                    throw new IllegalStateException("'" + name + "' is referenced but not present in the schema -- "
                            + "TsonSchemaLinker should already have rejected this before compilation ever started");
                }
                TsonValueReader<?> built;
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

        private TsonValueReader<?> build(String name, TypeDefinition definition) {
            Top body = definition.body();
            if (body instanceof Reference r) {
                return resolve(r.target().name());
            }
            return metaSchema.create(name, definition, this::resolve);
        }
    }
}
