package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@code constructor name -> TsonParserFactory} table, itself a {@link TsonParserFactory} --
 * what {@link TsonSchemaCompiler} actually consults to turn a resolved entry's own shape into a
 * compiled parser, keyed by the same name a meta-schema itself uses for that constructor (§4.1/
 * §5.5's {@code record}/{@code array}/{@code integer_type}/... vocabulary), recovered from the
 * resolved body's own {@code @Typename} via {@link #typenameOf} -- not from whatever name a
 * *particular* declaration happened to construct through. That distinction matters: {@code set},
 * {@code array_min}, {@code array_max}, and {@code array_ranged} all resolve to an {@link
 * io.ltr8.tson.schema.meta.ArrayBody} body regardless of which of them produced a given instance,
 * so they all correctly share the exact same {@code "array"} factory entry -- there is no separate
 * {@code "set"} entry to register at all, since {@code set} was never a distinct resolved *shape*,
 * only a distinct declared *name*.
 *
 * <p><b>Implements {@link TsonParserFactory} itself</b> (2026-07-27, on the user's own explicit
 * direction) -- {@link #create} does exactly what a caller used to do by hand (look the right
 * factory up via {@link #require}, then call it), so {@link TsonSchemaCompiler} makes one uniform
 * call regardless of whether it's holding a full registry or a single-shape implementation; see
 * {@link TsonParserFactory}'s own Javadoc for the fuller reasoning.
 *
 * <p>Keyed by name (not by {@code Class<? extends Top>}) specifically so this can grow past today's
 * fixed, closed set without a code change to this class -- meta-kernel and a governing meta-schema
 * like {@code meta.tn1} between them declare every constructor this project currently knows how to
 * build a factory for, but nothing about this shape assumes that particular set is final (a user's
 * own meta-schema importing {@code meta.tn1} and declaring further constructors of its own is a
 * real, if distant, future case -- registering an actual new factory for a genuinely novel shape
 * still needs a new Java class to build one from, which this registry doesn't attempt to solve).
 *
 * <p>{@link #forMetaSchema} is the "only make available what this schema actually declares" half of
 * the design: rather than one universal, always-everything table baked into the compiler, a
 * governing meta-schema's own {@code ~}-marked entries are walked, and each one's constructor name
 * is required to already exist in a caller-supplied "every factory this build of the library knows
 * how to construct" table -- {@link #require} throwing there, at registry-*construction* time,
 * turns a missing factory (e.g. one of the atom-constraint families that has no {@code
 * resolver.vocab} parser yet) into an immediate, specific error tied to the meta-schema entry that
 * needed it, rather than a generic "no factory for X" surfacing arbitrarily later during
 * compilation of some unrelated schema that happens to use it.
 */
public final class TsonParserFactoryRegistry implements TsonParserFactory {

    private final Map<String, TsonParserFactory> factories;

    private TsonParserFactoryRegistry(Map<String, TsonParserFactory> factories) {
        this.factories = factories;
    }

    /** @throws IllegalStateException if no factory is registered for {@code constructorName} */
    public TsonParserFactory require(String constructorName) {
        TsonParserFactory factory = factories.get(constructorName);
        if (factory == null) {
            throw new IllegalStateException("no TsonParserFactory registered for constructor '"
                    + constructorName + "'");
        }
        return factory;
    }

    /** Looks {@code typeName} up via {@link #require} and delegates straight to it. */
    @Override
    public TsonSchemaTypeParser<?> create(String typeName, String name, TypeDefinition definition, CompilationContext ctx) {
        return require(typeName).create(typeName, name, definition, ctx);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Every composite kind (`array`/`map`/`tuple`/`choice`) plus every atom-family constant {@link
     * AtomTypeParser} declares, *except* `record` and `enum` -- the two entries whose factory
     * genuinely differs by mode. `record`: DOM vs. object-binding, see {@link
     * RecordParser.RecordShapeFactory}. `enum`: every instance reads identically in both modes
     * *except* `boolean` itself ({@link AtomTypeParser#ENUM_OBJECT_MODE}'s own Javadoc) -- DOM mode
     * has no target Java type to reconcile `"true"`/`"false"` against, so it keeps producing
     * {@code String} uniformly ({@link AtomTypeParser#ENUM}); only object mode needs the name-keyed
     * branch.
     *
     * <p>{@code public}, unlike the rest of this class's own internal assembly, specifically so
     * {@code io.ltr8.tson.parser.bind} (object-binding mode, moved out of this package 2026-07-27)
     * can build its own registry from the identical composite/atom-family baseline {@link #dom()}
     * uses, without this package needing any awareness object-binding mode exists at all. Each
     * caller appends its own `"record"`/`"enum"` registrations on top.
     */
    public static Builder withoutRecordOrEnum() {
        return builder()
                .register("array", ArrayParser.FACTORY)
                .register("map", MapParser.FACTORY)
                .register("tuple", TupleParser.FACTORY)
                .register("choice", ChoiceParser.FACTORY)
                .register("integer_type", AtomTypeParser.INTEGER_TYPE)
                .register("text_type", AtomTypeParser.TEXT_TYPE)
                .register("decimal_type", AtomTypeParser.DECIMAL_TYPE)
                .register("float_type", AtomTypeParser.FLOAT_TYPE)
                .register("rational_type", AtomTypeParser.RATIONAL_TYPE)
                .register("uuid_type", AtomTypeParser.UUID_TYPE)
                .register("binary", AtomTypeParser.BINARY)
                .register("date_type", AtomTypeParser.DATE_TYPE)
                .register("time_type", AtomTypeParser.TIME_TYPE)
                .register("datetime_type", AtomTypeParser.DATETIME_TYPE)
                .register("duration_type", AtomTypeParser.DURATION_TYPE)
                .register("uri_type", AtomTypeParser.URI_TYPE)
                .register("regex_type", AtomTypeParser.REGEX_TYPE)
                .register("unit", AtomTypeParser.UNIT);
    }

    /**
     * Every DOM-mode factory this build of the library actually has -- `record` producing a plain
     * {@code Map<String, Object>}, same as every other composite/atom-family factory {@link
     * #withoutRecordOrEnum()} provides. Previously hand-duplicated as a private
     * {@code fullRegistry()} helper in several test classes (`MetaKernelEndToEndTest`, {@code
     * SchemaValidatingParserTest}, ...) and, as of {@code TsonCompiledRegistry}, real production code too --
     * factored out here once a fourth/fifth copy made the duplication worth closing. Not the *only*
     * legitimate registry a caller might build (a caller reading only a narrow slice of a schema can
     * still assemble a smaller one directly via {@link #builder}), just the canonical "everything
     * this build knows how to construct in DOM mode" one. Object-binding mode's own equivalent lives
     * in {@code io.ltr8.tson.parser.bind} (moved out of this class 2026-07-27) -- this class has no
     * dependency on it, and no awareness it exists.
     */
    public static TsonParserFactoryRegistry dom() {
        return withoutRecordOrEnum()
                .register("record", RecordParser.FACTORY)
                .register("enum", AtomTypeParser.ENUM)
                .build();
    }

    /**
     * Scopes {@code available} (every factory this build of the library knows how to construct,
     * for one particular mode -- object-binding/DOM/validation, see {@link TsonParserFactory}'s own
     * Javadoc) down to exactly the constructors {@code metaSchema} itself declares (its own
     * entries with {@link TypeDefinition#constructor()} true) -- erroring immediately, per entry,
     * if one of them has no matching factory in {@code available}.
     */
    public static TsonParserFactoryRegistry forMetaSchema(TsonSchema metaSchema, TsonParserFactoryRegistry available) {
        Builder scoped = builder();
        for (Map.Entry<String, TypeDefinition> entry : metaSchema.entries().entrySet()) {
            TypeDefinition definition = entry.getValue();
            if (!definition.constructor()) {
                continue;
            }
            String constructorName = typenameOf(definition.body());
            try {
                scoped.register(constructorName, available.require(constructorName));
            } catch (IllegalStateException e) {
                throw new IllegalStateException("'" + metaSchema.id()
                        + "' declares constructor '" + entry.getKey() + "' (shape '" + constructorName
                        + "') but no TsonParserFactory is available for it", e);
            }
        }
        return scoped.build();
    }

    /** The constructor name a resolved body identifies as -- its own {@code @Typename}, e.g. {@code "record"}/{@code "integer_type"}. */
    public static String typenameOf(Top body) {
        Typename typename = body.getClass().getAnnotation(Typename.class);
        if (typename == null) {
            throw new IllegalStateException(body.getClass() + " has no @Typename -- every Top leaf must carry one");
        }
        return typename.name();
    }

    public static final class Builder {
        private final Map<String, TsonParserFactory> factories = new LinkedHashMap<>();

        public Builder register(String constructorName, TsonParserFactory factory) {
            factories.put(constructorName, factory);
            return this;
        }

        public TsonParserFactoryRegistry build() {
            return new TsonParserFactoryRegistry(Map.copyOf(factories));
        }
    }
}
