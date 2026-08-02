package io.ltr8.tson.compiler;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryResolver;
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
 * <p>A constructor this meta-schema does not declare ({@link #constructor} returns {@code null}) is
 * out of a governed schema's scope: {@link TsonSchemaCompiler} admits it only if the schema being
 * compiled declares it itself (meta.tn compiling {@code float32 => !float_type {}}, where {@code
 * float_type} is meta.tn's own new constructor, absent from its governing meta, meta-kernel -- sourced
 * then from {@link #globalResolver}), and rejects it otherwise. This is where scoping is enforced.
 */
public final class TsonCompiledMetaSchema extends TsonCompiledSchema {

    private final ValueReaderFactoryResolver resolver;
    private final Map<String, ReaderResolver> constructors;

    TsonCompiledMetaSchema(TsonCompiledSchema base, ValueReaderFactoryResolver resolver) {
        super(base.linkedSchema(), base.entries());
        this.resolver = resolver;
        this.constructors = buildConstructors(this, resolver);
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
     * The factory for constructor {@code name} if this meta-schema declares it, else {@code null} --
     * scoped strictly to this meta-schema's own vocabulary, with no global fallback. An absent result
     * tells {@link TsonSchemaCompiler} the constructor is out of this governing meta's scope, to be
     * admitted only if the schema being compiled declares it itself (sourced then from {@link
     * #globalResolver}) and rejected otherwise. Package-private: only {@link TsonSchemaCompiler} (same
     * package) consults it.
     */
    ValueReaderFactory constructor(String name) {
        ReaderResolver declared = constructors.get(name);
        return declared == null ? null : declared.factory();
    }

    /**
     * The library's full factory set -- {@link TsonSchemaCompiler}'s source for a constructor the
     * *compiling* schema declares itself (e.g. meta.tn's own {@code float_type}), one this governing
     * meta's scoped vocabulary does not include. Package-private: only {@link TsonSchemaCompiler}
     * consults it, and only after finding the constructor absent from {@link #constructor}.
     */
    ValueReaderFactoryResolver globalResolver() {
        return resolver;
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
