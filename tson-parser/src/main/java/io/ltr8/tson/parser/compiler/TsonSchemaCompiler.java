package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.TsonValueReader;
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
 * vocabulary: this class is the verb, {@link TsonCompiledSchema} is the noun it produces. The
 * per-compilation mutable state (cycle-detection bookkeeping) lives in a private nested {@link
 * Compilation} helper, one instance per {@link #compile} call, discarded once it returns --
 * {@link TsonCompiledSchema} itself holds nothing but the finished, immutable result.
 *
 * <p><b>Eager, not lazy.</b> {@link #compile} walks every one of {@code linkedSchema.schema()
 * .entries()} and resolves each before returning, so a caller that only ever reads a handful of a
 * large schema's own types still gets the same assurance that every other entry compiles too.
 *
 * <p>A build failure for one specific entry doesn't abort the whole walk -- {@link
 * Compilation#resolve} catches it and substitutes an {@link ErrorParser}, so the schema as a whole
 * still compiles; only actually reading a value against that one entry fails, at that point. This
 * covers both a constructor with no registered {@link TsonParserFactory} at all, and a factory
 * that's registered but rejects this particular entry.
 *
 * <p>Dispatch to a factory is uniform across every constructor -- atom and composite alike --
 * driven entirely by whatever {@link TsonParserFactory} {@link #compile} was given, keyed by the
 * resolved body's own constructor name (see {@link TsonParserFactoryRegistry#typenameOf}). One
 * non-factory case is checked first: {@link Reference} (a bare {@code name => other_name} entry,
 * §8.3) delegates straight to the target's own handle.
 *
 * <p>A declaration's own body is always compiled, whether or not it also has subtypes. A type with
 * known subtypes ({@link TypeDefinition#subtypes}, the reverse composition index) gets its ordinary
 * body reader wrapped in a {@link VariantParser}: a value with no explicit {@code !typeName}
 * annotation, or one naming the declaration itself, reads against the ordinary body reader
 * directly; a type-ref naming a known subtype dispatches there instead. This is schema-driven, not
 * Java-driven, deliberately -- {@code subtypes} is the schema's own, language-agnostic
 * representation of "who composes with this," which is what makes this layer's polymorphism
 * handling portable to a from-scratch implementation in another language.
 */
public final class TsonSchemaCompiler {

    private TsonSchemaCompiler() {
    }

    /**
     * Compiles {@code linkedSchema} against {@code factory}, eagerly -- see this class's own "Eager,
     * not lazy" note. {@code factory} is typically a {@link TsonParserFactoryRegistry} (itself a
     * {@link TsonParserFactory}), but any single-shape {@link TsonParserFactory} works too.
     *
     * <p>Requires a {@link TsonLinkedSchema}, not a bare {@link TsonSchema} -- every {@code type_ref}
     * reachable from a body must already be argument-free (materialization already flattened any
     * {@code <...>} application into a reference to a synthesized entry) and every name a body
     * refers to must actually be present in {@code linkedSchema.schema().entries()}; {@link
     * Compilation#resolve} treats a referenced-but-missing name as a bug, not a normal failure. Not
     * runtime-enforced -- {@link TsonLinkedSchema}'s canonical constructor is public -- but every
     * real caller gets one via {@code TsonSchemaLinker.link}/{@code TsonSchemaRegistry#register}.
     */
    public static TsonCompiledSchema compile(TsonLinkedSchema linkedSchema, TsonParserFactory factory) {
        Compilation compilation = new Compilation(linkedSchema.schema(), factory);
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
        private final TsonParserFactory factory;
        private final Map<String, TsonValueReader<?>> finished = new LinkedHashMap<>();
        private final Set<String> building = new LinkedHashSet<>();

        Compilation(TsonSchema schema, TsonParserFactory factory) {
            this.schema = schema;
            this.factory = factory;
        }

        /**
         * The one place cycles get broken. Three cases: {@code name} already has a finished parser
         * (hand back a {@link ParserHandle.Direct} wrapping it -- no rebuilding, no rewalking);
         * {@code name} is already on {@link #building} (this call is itself nested inside building
         * {@code name}, directly or transitively -- recursing further would never terminate, so
         * hand back a {@link ParserHandle.Indirect} instead, a lazy lookup against {@link
         * #finished} that only ever actually runs once this whole {@link Compilation} -- including
         * {@code name} itself -- has moved on); otherwise, not started yet, so build it right now,
         * with {@code name} pushed onto {@link #building} for the duration so a cycle back to it
         * from somewhere inside its own construction is caught by the middle case instead of
         * recursing forever.
         *
         * <p><b>A {@link #build} failure for {@code name} itself never propagates out of this
         * method</b> -- caught and replaced with an {@link ErrorParser} wrapping the original
         * exception, so one entry this build of the library can't actually construct a parser for
         * (a missing {@link TsonParserFactory}, or one that's registered but rejects this
         * particular entry, e.g. object-binding mode's own eager per-entry validation) never blocks
         * the eager, whole-schema walk {@link #compile} performs from finishing. A missing/absent
         * *referenced* name (the {@code definition == null} case just below) is a different,
         * stricter thing -- a genuine compiler-bug signal ({@code TsonSchemaLinker} should already
         * have rejected it), not "this build doesn't support constructor X yet" -- and still
         * propagates immediately, uncaught.
         */
        ParserHandle<?> resolve(String name) {
            TsonValueReader<?> done = finished.get(name);
            if (done != null) {
                return new ParserHandle.Direct<>(done);
            }
            if (!building.add(name)) {
                return new ParserHandle.Indirect<>(name, finished);
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
                    built = new ErrorParser(name, e);
                }
                finished.put(name, built);
                return new ParserHandle.Direct<>(built);
            } finally {
                building.remove(name);
            }
        }

        private TsonValueReader<?> build(String name, TypeDefinition definition) {
            Top body = definition.body();
            if (body instanceof Reference r) {
                return resolve(r.target().name());
            }
            TsonValueReader<?> ownParser =
                    factory.create(TsonParserFactoryRegistry.typenameOf(body), name, definition, this::resolve);
            if (definition.subtypes().isEmpty()) {
                return ownParser;
            }
            return VariantParser.forSubtypes(name, definition, ownParser, this::resolve);
        }
    }
}
