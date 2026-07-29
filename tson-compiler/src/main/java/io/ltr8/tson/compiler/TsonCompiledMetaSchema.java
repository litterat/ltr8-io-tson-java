package io.ltr8.tson.compiler;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.compiler.config.ValueReaderFactoryResolver;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.HashMap;
import java.util.Map;

/**
 * A compiled meta-schema -- both halves a {@code !!meta}-governed schema needs from its own
 * governing meta: reading an instance of one of its declared constructors ({@link #reader}, e.g.
 * resolving a construction like {@code !record {...}} against the {@code record} constructor
 * meta-kernel itself declares), and building a compiled reader for some *other* schema's own
 * declaration during {@link TsonSchemaCompiler#compile} ({@link #create}).
 *
 * <p><b>{@link #reader} and {@link #create} are scoped completely differently, on purpose.</b>
 * {@link #reader} is scoped to exactly what {@code compiledSchema} itself declares -- the structure
 * namespace's own rule (§3.3.1): a constructor-application target resolves against the *governing*
 * meta-schema, never anything broader. {@link #create} is not scoped that way at all -- it resolves
 * a constructor name against {@code resolver}, the full, global {@link ValueReaderFactoryResolver}
 * this meta-schema was built with. That distinction is load-bearing, not incidental: a schema
 * governed by this meta-schema is free to declare its own *new* constructors that the governing meta
 * itself never mentions (meta.tn1, governed by meta-kernel, declares {@code float_type} -- a
 * constructor meta-kernel's own 12 don't include at all). Scoping {@link #create} to {@link #reader}'s
 * own narrower set would make compiling meta.tn1 itself fail the moment it reached its own first
 * {@code float32 => !float_type {...}} declaration. This mirrors the old {@code
 * reader.TsonSchemaCompiler}'s own behavior exactly -- it always compiled every schema (meta-kernel
 * included) against one full, global {@code TsonParserFactoryRegistry}, never a meta-scoped subset.
 */
public final class TsonCompiledMetaSchema {

    private final TsonCompiledSchema compiledSchema;
    private final ValueReaderFactoryResolver resolver;
    private Map<String, TsonValueReader<?>> readers;

    public TsonCompiledMetaSchema(TsonCompiledSchema compiledSchema, ValueReaderFactoryResolver resolver) {
        this.compiledSchema = compiledSchema;
        this.resolver = resolver;
    }

    /**
     * Bootstraps a compiled meta-schema for meta-kernel itself -- the one deliberate circularity
     * (§1.5): meta-kernel's own {@code !!meta} names itself, so there's no already-compiled
     * governing meta to build one the ordinary way (the constructor above needs a real {@link
     * TsonCompiledSchema}, which needs a governing meta to compile against -- exactly what doesn't
     * exist yet here). A throwaway meta-schema wrapping an empty {@link TsonCompiledSchema} stands
     * in as {@link TsonSchemaCompiler#compile}'s own required parameter -- safe, since {@link
     * #create} never actually reads anything from the *wrapped schema* itself, only from {@code
     * resolver}, which is the same {@code resolver} either way.
     *
     * <p>{@code linkedMetaKernel} is expected to come from {@code TsonSchemaLinker#linkBootstrap},
     * not the ordinary {@code link} -- that would need meta-kernel already registered somewhere to
     * resolve its own self-referential {@code !!meta} against, which is exactly the circularity this
     * method exists to break.
     */
    public static TsonCompiledMetaSchema bootstrap(TsonLinkedSchema linkedMetaKernel, ValueReaderFactoryResolver resolver) {
        TsonCompiledSchema placeholder = new TsonCompiledSchema(linkedMetaKernel, Map.of());
        TsonCompiledMetaSchema bootstrapMeta = new TsonCompiledMetaSchema(placeholder, resolver);
        TsonCompiledSchema compiledMetaKernel = TsonSchemaCompiler.compile(linkedMetaKernel, bootstrapMeta);
        return new TsonCompiledMetaSchema(compiledMetaKernel, resolver);
    }

    public TsonSchema schema() {
        return compiledSchema.schema();
    }

    /** The wrapped {@link TsonCompiledSchema} directly, for a caller that needs to read *any* entry -- not just the constructor-declared subset {@link #reader} exposes. */
    public TsonCompiledSchema compiledSchema() {
        return compiledSchema;
    }

    /**
     * Reads an instance of {@code name}, one of this meta-schema's own declared constructors --
     * {@code name} is the declaration's own name (e.g. {@code "record"}), not necessarily its
     * resolved body's own constructor name (though for every real meta-kernel/meta-schema
     * declaration today the two are identical).
     *
     * <p>Builds its own {@code name -> TsonValueReader} lookup lazily, on first call, rather than in
     * the constructor -- a throwaway instance built to wrap a not-yet-compiled placeholder (see
     * {@link #bootstrap}) is only ever consulted via {@link #create}, which never touches this
     * lookup at all; building it eagerly would mean walking {@code compiledSchema}'s own entries and
     * calling {@link TsonCompiledSchema#get} before a single one of them has actually been compiled.
     */
    public TsonValueReader<?> reader(String name) {
        if (readers == null) {
            readers = buildReaders(compiledSchema);
        }
        TsonValueReader<?> reader = readers.get(name);
        if (reader == null) {
            throw new IllegalArgumentException(
                    "'" + name + "' is not a constructor '" + schema().id() + "' declares");
        }
        return reader;
    }

    /**
     * Builds a compiled reader for {@code name}, a declaration in some other schema being compiled
     * against this meta-schema -- dispatched by {@code typeDefinition}'s own resolved constructor
     * name ({@code typenameOf(typeDefinition.body())}), not {@code name} itself (they coincide only
     * for a meta-schema's own declarations, where a declaration and the constructor it defines share
     * one name by construction; every other real declaration's own name differs from its
     * constructor, e.g. {@code "float32"} constructed via {@code "float_type"}).
     */
    public TsonValueReader<?> create(String name, TypeDefinition typeDefinition, TsonValueReaderResolver readerResolver) {
        String constructorName = typenameOf(typeDefinition.body());
        return resolver.resolve(constructorName).create(name, typeDefinition, readerResolver);
    }

    private static Map<String, TsonValueReader<?>> buildReaders(TsonCompiledSchema compiledSchema) {
        Map<String, TsonValueReader<?>> readers = new HashMap<>();
        for (Map.Entry<String, TypeDefinition> entry : compiledSchema.schema().entries().entrySet()) {
            if (entry.getValue().constructor()) {
                readers.put(entry.getKey(), compiledSchema.get(entry.getKey()));
            }
        }
        return Map.copyOf(readers);
    }

    /** The constructor name a resolved body identifies as -- its own {@code @Typename}, e.g. {@code "record"}/{@code "float_type"}. */
    private static String typenameOf(Top body) {
        Typename typename = body.getClass().getAnnotation(Typename.class);
        if (typename == null) {
            throw new IllegalStateException(body.getClass() + " has no @Typename -- every Top leaf must carry one");
        }
        return typename.name();
    }
}
