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
 * A compiled {@link TsonSchema} -- {@code Map<String, TypeDefinition>} lifted to {@code Map<String,
 * TsonTypeParser<?>>}, where every parser's own references to other entries are real Java object
 * references (a {@link ParserHandle}), not further name lookups. This is the "compile the schema
 * once, read many data documents against it fast" layer sitting on top of {@code SchemaResolver}'s
 * own per-declaration resolution and {@code SchemaRegistry}'s whole-schema materialization/
 * validation -- the actual Class 2 (schema-validating) data parser this project doesn't have yet
 * is expected to be built on top of a {@link TsonSchemaParser}, not directly on {@code TsonSchema}.
 *
 * <p><b>Must be compiled from an already-materialized, already-validated {@link TsonSchema}</b> --
 * i.e. {@code SchemaRegistry}'s own output, never a raw {@code SchemaResolver.resolveAll} result
 * directly. Two reasons: every {@code type_ref} reachable from a body needs to already be
 * argument-free (materialization already flattened any {@code <...>} application into a reference
 * to a synthesized entry -- see {@code SchemaRegistry}'s own Javadoc), since nothing here
 * re-implements that; and every name a body refers to needs to actually be present in {@code
 * schema.entries()} (validation already confirmed this), since {@link Compiler#resolve} (reached
 * through {@link #get}) treats a referenced-but-missing name as a bug, not a normal failure to
 * report.
 *
 * <p>Dispatch to a factory is uniform across every constructor -- atom and composite alike --
 * driven entirely by {@link ParserFactoryRegistry}, keyed by the resolved body's own constructor
 * name (see {@link ParserFactoryRegistry#typenameOf}). Two non-registry cases are checked first:
 * {@link Reference} (a bare {@code name => other_name} entry, §8.3, never produced by a {@code
 * !constructor {...}} application -- pure delegation to the target's own handle, no factory
 * involved) and a declaration with open type parameters (non-empty {@link
 * TypeDefinition#parameters}), whose own body has no concrete shape to read -- routed to {@link
 * VariantParser} instead, dispatching over its known subtypes rather than its own fields.
 *
 * <p><b>Lazy, not eager</b> -- {@link #compile} builds nothing up front; {@link #get} triggers
 * {@link Compiler#resolve} for whichever name is actually requested, on first use, memoized after
 * (the same mechanism {@link Compiler#resolve} already needs internally for its own recursive
 * dependency resolution -- there was never a separate "build everything" step to keep, once that
 * existed). This matters beyond laziness-as-an-optimization: a real schema the size of meta-kernel
 * declares far more constructors (`unit`/`map`/`tuple`/`choice`/`text_type`/`uri_type`/...) than
 * any one {@link ParserFactoryRegistry} necessarily has factories for yet -- eagerly building every
 * entry would demand a factory for all of them just to compile a schema that only ever gets read
 * through a handful of its own types. A caller only pays for, and only needs a registry covering,
 * whatever it actually calls {@link #get} for.
 *
 * <p>Not thread-safe -- {@link Compiler}'s own {@code finished}/{@code building} state is plain,
 * unsynchronized mutable state, mutated on every {@link #get} call now that building happens
 * on-demand rather than once up front. Fine for "compile once, then read documents from one
 * thread"; a concurrent caller needs its own external synchronization until/unless that becomes a
 * real requirement.
 */
public final class TsonSchemaParser {

    private final TsonSchema schema;
    private final Compiler compiler;

    private TsonSchemaParser(TsonSchema schema, ParserFactoryRegistry registry) {
        this.schema = schema;
        this.compiler = new Compiler(schema, registry);
    }

    public TsonTypeParser<?> get(String typeName) {
        if (!schema.entries().containsKey(typeName)) {
            throw new IllegalArgumentException("'" + typeName + "' is not in this compiled schema");
        }
        return compiler.resolve(typeName);
    }

    /** Wraps {@code schema}/{@code registry} for on-demand compilation -- see this class's own "Lazy, not eager" note. */
    public static TsonSchemaParser compile(TsonSchema schema, ParserFactoryRegistry registry) {
        return new TsonSchemaParser(schema, registry);
    }

    /** {@code finished}/{@code building} accumulate across every {@link #get} call on the owning {@link TsonSchemaParser} -- shared compilation state, not reset per call. */
    private static final class Compiler {

        private final TsonSchema schema;
        private final ParserFactoryRegistry registry;
        private final Map<String, TsonTypeParser<?>> finished = new LinkedHashMap<>();
        private final Set<String> building = new LinkedHashSet<>();

        Compiler(TsonSchema schema, ParserFactoryRegistry registry) {
            this.schema = schema;
            this.registry = registry;
        }

        /**
         * The one place cycles get broken. Three cases: {@code name} already has a finished parser
         * (hand back a {@link ParserHandle.Direct} wrapping it -- no rebuilding, no rewalking);
         * {@code name} is already on {@link #building} (this call is itself nested inside building
         * {@code name}, directly or transitively -- recursing further would never terminate, so
         * hand back a {@link ParserHandle.Indirect} instead, a lazy lookup against {@link
         * #finished} that only ever actually runs once compilation as a whole -- including {@code
         * name} itself -- has moved on); otherwise, not started yet, so build it right now, with
         * {@code name} pushed onto {@link #building} for the duration so a cycle back to it from
         * somewhere inside its own construction is caught by the middle case instead of recursing
         * forever.
         */
        ParserHandle<?> resolve(String name) {
            TsonTypeParser<?> done = finished.get(name);
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
                            + "SchemaValidator should already have rejected this before compilation ever started");
                }
                TsonTypeParser<?> built = build(name, definition);
                finished.put(name, built);
                return new ParserHandle.Direct<>(built);
            } finally {
                building.remove(name);
            }
        }

        private TsonTypeParser<?> build(String name, TypeDefinition definition) {
            Top body = definition.body();
            if (body instanceof Reference r) {
                return resolve(r.target().name());
            }
            if (!definition.parameters().isEmpty()) {
                return VariantParser.forSubtypes(name, definition, this::resolve);
            }
            TsonParserFactory factory = registry.require(ParserFactoryRegistry.typenameOf(body));
            return factory.create(name, definition, this::resolve);
        }
    }
}
