package io.ltr8.tson.compiler;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.compiler.config.ValueReaderFactoryResolver;
import io.ltr8.tson.compiler.reader.ValueReaderFactory;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.HashMap;
import java.util.Map;

/**
 * A compiled meta-schema -- a {@link TsonCompiledSchema} plus the scoped constructor vocabulary a
 * schema governed by it (its own {@code !!meta} target) is allowed to see. That vocabulary is the
 * whole point: the global {@link ValueReaderFactoryResolver} is every structure this library can
 * build, but a governing meta-schema declares only *some* of them, and a schema saying {@code
 * !!meta:X} may construct only with the constructors X declares -- not with anything the global
 * registry happens to support.
 *
 * <p>Each declared constructor is held as a {@link ReaderResolver} pairing its two compiled facets:
 * the reader that reads an *instance* of the constructor ({@link #reader}, used while resolving a
 * governed schema's {@code !C value} declarations), and the factory that builds a reader for an
 * entry whose own body *is* such a construction ({@link #constructor}, consulted by {@link
 * TsonSchemaCompiler} when it compiles a governed schema). Combining them keeps both facets scoped
 * to the same declared set.
 *
 * <p>A constructor this meta-schema does not declare falls back to the full global factory set
 * (see {@link #constructor}) -- e.g. one the schema being compiled declares itself (meta.tn
 * compiling {@code float32 => !float_type {}}, where {@code float_type} is meta.tn's own new
 * constructor, absent from meta.tn's governing meta, meta-kernel). Preferring the scoped factory
 * over this fallback is behavior-preserving today; the fallback is the seam through which
 * out-of-scope constructors are later rejected outright.
 */
public final class TsonCompiledMetaSchema extends TsonCompiledSchema {

    private final ValueReaderFactoryResolver resolver;
    private final Map<String, ReaderResolver> constructors;

    public TsonCompiledMetaSchema(TsonCompiledSchema base, ValueReaderFactoryResolver resolver) {
        super(base.linkedSchema(), base.entries());
        this.resolver = resolver;
        this.constructors = buildConstructors(this, resolver);
    }

    /**
     * Bootstraps a compiled meta-schema for meta-kernel itself -- the one deliberate circularity
     * (§1.5): meta-kernel's own {@code !!meta} names itself, so there's no already-compiled
     * governing meta to build one the ordinary way. A throwaway meta wrapping an empty {@link
     * TsonCompiledSchema} stands in as {@link TsonSchemaCompiler#compile}'s governing-meta
     * argument; its scoped vocabulary is empty (the placeholder has no readers), so every one of
     * meta-kernel's own constructors is compiled via the own-declared path against {@code resolver}
     * -- exactly the base case, since meta-kernel declares its whole vocabulary itself.
     *
     * <p>{@code linkedMetaKernel} is expected to come from {@code TsonSchemaLinker#linkBootstrap},
     * not the ordinary {@code link} -- that would need meta-kernel already registered somewhere to
     * resolve its own self-referential {@code !!meta} against, which is exactly the circularity
     * this method exists to break.
     */
    public static TsonCompiledMetaSchema bootstrap(TsonLinkedSchema linkedMetaKernel, ValueReaderFactoryResolver resolver) {
        TsonCompiledSchema placeholder = new TsonCompiledSchema(linkedMetaKernel, Map.of());
        TsonCompiledMetaSchema bootstrapMeta = new TsonCompiledMetaSchema(placeholder, resolver);
        TsonCompiledSchema compiledMetaKernel = TsonSchemaCompiler.compile(linkedMetaKernel, bootstrapMeta);
        return new TsonCompiledMetaSchema(compiledMetaKernel, resolver);
    }

    /**
     * This meta-schema as a plain {@link TsonCompiledSchema} -- it *is* one (this class extends it),
     * so this returns {@code this}. Retained so a caller reading an arbitrary entry ({@code
     * compiledSchema().get(name)}) keeps a stable spelling; {@link #reader} is the scoped,
     * constructor-only view.
     */
    public TsonCompiledSchema compiledSchema() {
        return this;
    }

    /**
     * Reads an instance of {@code name}, one of this meta-schema's own declared constructors --
     * {@code name} is the declaration's own name (e.g. {@code "record"}), not necessarily its
     * resolved body's own constructor name (though for every real meta-kernel/meta-schema
     * declaration today the two are identical).
     */
    public TsonValueReader<?> reader(String name) {
        ReaderResolver constructor = constructors.get(name);
        if (constructor == null) {
            throw new IllegalArgumentException(
                    "'" + name + "' is not a constructor '" + schema().id() + "' declares");
        }
        return constructor.instanceReader();
    }

    /**
     * The factory for constructor {@code name}: this meta-schema's own scoped vocabulary if it
     * declares {@code name}, else the global factory set as a fallback (which itself fails for a
     * truly unknown constructor). Mirrors {@link #reader}'s look-up-or-fail shape. The fallback is
     * the seam a later stage tightens into rejecting an out-of-scope constructor outright, once a
     * real governing meta is always present. Package-private: only {@link TsonSchemaCompiler}
     * (same package) consults it.
     */
    ValueReaderFactory constructor(String name) {
        ReaderResolver declared = constructors.get(name);
        return declared != null ? declared.factory() : resolver.resolve(name);
    }

    /**
     * This meta-schema's scoped constructor vocabulary: every {@code constructor: true} entry it
     * declares, paired with its instance reader and its factory. A constructor with no factory at
     * all is left out rather than aborting the build -- a schema that actually uses it defers to a
     * per-entry {@link io.ltr8.tson.compiler.reader.ErrorReader} at compile-dispatch time, the same
     * treatment any other unbuildable entry gets (the bundled chain never hits this: its own gap
     * constructors resolve to an {@code ErrorReader} factory rather than throwing).
     */
    private static Map<String, ReaderResolver> buildConstructors(
            TsonCompiledSchema compiledSchema, ValueReaderFactoryResolver resolver) {
        Map<String, ReaderResolver> constructors = new HashMap<>();
        for (Map.Entry<String, TypeDefinition> entry : compiledSchema.schema().entries().entrySet()) {
            if (!entry.getValue().constructor()) {
                continue;
            }
            String name = entry.getKey();
            compiledSchema.find(name).ifPresent(instanceReader -> {
                try {
                    constructors.put(name, new ReaderResolver(instanceReader, resolver.resolve(name)));
                } catch (RuntimeException noFactory) {
                    // Deliberately swallowed -- see this method's own Javadoc.
                }
            });
        }
        return Map.copyOf(constructors);
    }

    /**
     * The constructor name a resolved body identifies as -- its own {@code @Typename}, e.g. {@code
     * "record"}/{@code "float_type"}. Package-private so {@link TsonSchemaCompiler} dispatches a
     * body to its factory the same way this class scopes one.
     */
    static String typenameOf(Top body) {
        Typename typename = body.getClass().getAnnotation(Typename.class);
        if (typename == null) {
            throw new IllegalStateException(body.getClass() + " has no @Typename -- every Top leaf must carry one");
        }
        return typename.name();
    }

    /**
     * A declared constructor's two compiled facets, held together only while this class builds its
     * scoped vocabulary: the reader for an *instance* of it ({@link #reader}) and the factory that
     * builds a reader for an entry that *is* a construction of it ({@link #constructor}). A private
     * convenience -- neither the record nor its facet split leaks past this class.
     */
    private record ReaderResolver(TsonValueReader<?> instanceReader, ValueReaderFactory factory) {
    }
}
