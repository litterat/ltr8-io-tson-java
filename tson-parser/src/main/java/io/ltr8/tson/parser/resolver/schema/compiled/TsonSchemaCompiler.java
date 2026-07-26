package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Compiles an already-materialized, already-validated {@link TsonSchema} into a {@link
 * TsonCompiledSchema} -- the "compile" stage of this project's own parse -&gt; resolve -&gt; link
 * -&gt; register -&gt; compile -&gt; read pipeline vocabulary: this class is the verb, {@link
 * TsonCompiledSchema} is the noun it produces. Unlike {@code TsonSchemaLinker.link}, the class this
 * verb belongs to genuinely needs its own private, per-compilation mutable state -- cycle-detection
 * bookkeeping (below) -- so (moved here from {@link TsonCompiledSchema} itself, 2026-07-27, on the
 * user's own explicit direction: "the compile step still lives in TsonCompiledSchema.. move the
 * compiler code into TsonSchemaCompiler") that state, and the recursive build logic that owns it,
 * live in a private nested {@link Compilation} helper -- one instance per {@link #compile} call,
 * discarded once it returns. {@link TsonCompiledSchema} itself is left holding nothing but the
 * finished result: a plain, already-built {@code Map<String, TsonSchemaTypeParser<?>>}, immutable
 * from the moment it's constructed.
 *
 * <p><b>Eager, not lazy</b> (flipped 2026-07-27, on the user's own explicit direction -- an earlier
 * version built nothing until {@code TsonCompiledSchema#get} was first called for a given name).
 * {@link #compile} now walks every one of {@code schema.entries()} and resolves each, so the whole
 * graph is built (and every {@code type_ref} it reaches proven reachable/buildable) before this
 * method returns -- not deferred to whenever a caller happens to ask for a given name. This is what
 * "if we build the graph upfront we can validate everything is there" actually means for a compiled
 * schema: a caller that only ever reads a handful of a large schema's own types still gets, up
 * front, the same assurance that every *other* entry compiles too -- these are schemas with tens or
 * hundreds of entries, not millions, so walking all of them costs nothing worth avoiding.
 *
 * <p>The real obstacle eager building used to run into: a real schema like meta-kernel declares
 * more constructors (`unit`/`map`/`tuple`/`choice`/`text_type`/`uri_type`/...) than any one {@link
 * TsonParserFactoryRegistry} necessarily has factories for yet, and (found empirically, once this
 * actually ran against the real, registered meta-kernel/meta.tn1 fixtures in object-binding mode)
 * a factory that *is* registered can still legitimately reject one particular entry -- {@code
 * ObjectRecordShapeFactory} deliberately never caches meta-kernel's own non-record-bound marker
 * entries like {@code top}/{@code atom} (see its own Javadoc), so building the ordinary {@code
 * "record"} factory against *those specific* entries throws, even though the same factory works
 * fine for every genuinely record-shaped one -- worth reviewing on its own terms later, per the
 * user's own note, not something this change tries to fix. {@link Compilation#resolve} solves both
 * the same way, not by staying lazy: any {@link RuntimeException} thrown while building one specific
 * {@code name} is caught right there and replaced with an {@link ErrorParser} wrapping it, rather
 * than aborting the whole eager walk. The schema as a whole still compiles in full; only actually
 * reading a value against one of the unsupported/rejected types fails, at that point, not before.
 *
 * <p>Dispatch to a factory is uniform across every constructor -- atom and composite alike --
 * driven entirely by whatever {@link TsonParserFactory} {@link #compile} was given, keyed by the
 * resolved body's own constructor name (see {@link TsonParserFactoryRegistry#typenameOf}) and
 * passed as that single call's own first argument -- see {@link TsonParserFactory}'s own Javadoc
 * for why the interface itself carries {@code typeName} now, not just this compiler's own call
 * site. One non-factory case is checked first: {@link Reference} (a bare {@code name =>
 * other_name} entry, §8.3, never produced by a {@code !constructor {...}} application -- pure
 * delegation to the target's own handle, no factory involved).
 *
 * <p><b>A declaration's own body is always compiled, whether or not it also has subtypes.</b> A
 * type with known subtypes (non-empty {@link TypeDefinition#subtypes}, populated by {@code
 * SchemaValidator.computeSubtypes} from the reverse composition index -- deliberately *not*
 * triggered by open type parameters, an earlier version of this class's own choice; see {@link
 * VariantParser}'s own Javadoc for why that was too narrow a signal) gets its ordinary body parser
 * wrapped in a {@link VariantParser}: a value with no explicit {@code !typeName} annotation, or one
 * naming the declaration itself, reads against that ordinary body parser directly (e.g. {@code top
 * => top & {}}'s own empty body resolves {@code {}} at a bare {@code top}-typed position, even
 * though {@code top} is also the supertype of everything else in the schema); a type-ref naming a
 * known subtype dispatches there instead, lazily. Schema-driven, not Java-driven -- this is
 * deliberately *not* implemented by leaning on {@code tson-bind}'s own sealed-interface/{@code
 * @Typename} union matching the way {@code DefinitionResolver}'s generic constructor-application
 * binding does one layer down: {@code subtypes} is the schema's own, language-agnostic
 * representation of "who composes with this," and using it here rather than Java class hierarchy
 * details is what makes this layer's own polymorphism handling portable to a from-scratch
 * implementation in another language, not an artifact of how this one happens to model {@code
 * schema.meta} in Java.
 */
public final class TsonSchemaCompiler {

    private TsonSchemaCompiler() {
    }

    /**
     * Compiles {@code schema} against {@code factory}, eagerly -- see this class's own "Eager, not
     * lazy" note. {@code factory} is typically a {@link TsonParserFactoryRegistry} (which is itself
     * a {@link TsonParserFactory}, see that class's own Javadoc), but doesn't have to be -- any
     * single-shape {@link TsonParserFactory} works too, e.g. for a test that only wants to compile
     * against one constructor.
     *
     * <p><b>Must be compiled from an already-materialized, already-validated {@link TsonSchema}</b>
     * -- i.e. {@code TsonSchemaRegistry}'s own output, never a raw {@code TsonSchemaResolver.resolveSchema}
     * result directly. Two reasons: every {@code type_ref} reachable from a body needs to already be
     * argument-free (materialization already flattened any {@code <...>} application into a
     * reference to a synthesized entry -- see {@code TsonSchemaRegistry}'s own Javadoc), since
     * nothing here re-implements that; and every name a body refers to needs to actually be present
     * in {@code schema.entries()} (validation already confirmed this), since {@link Compilation
     * #resolve} treats a referenced-but-missing name as a bug, not a normal failure to report.
     */
    public static TsonCompiledSchema compile(TsonSchema schema, TsonParserFactory factory) {
        Compilation compilation = new Compilation(schema, factory);
        for (String name : schema.entries().keySet()) {
            compilation.resolve(name);
        }
        return new TsonCompiledSchema(schema, Map.copyOf(compilation.finished));
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
        private final Map<String, TsonSchemaTypeParser<?>> finished = new LinkedHashMap<>();
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
            TsonSchemaTypeParser<?> done = finished.get(name);
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
                TsonSchemaTypeParser<?> built;
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

        private TsonSchemaTypeParser<?> build(String name, TypeDefinition definition) {
            Top body = definition.body();
            if (body instanceof Reference r) {
                return resolve(r.target().name());
            }
            TsonSchemaTypeParser<?> ownParser =
                    factory.create(TsonParserFactoryRegistry.typenameOf(body), name, definition, this::resolve);
            if (definition.subtypes().isEmpty()) {
                return ownParser;
            }
            return VariantParser.forSubtypes(name, definition, ownParser, this::resolve);
        }
    }
}
