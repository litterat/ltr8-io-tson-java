package io.ltr8.tson.compiler;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.compiler.reader.ErrorReader;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryResolver;
import io.ltr8.tson.compiler.reader.ValueReaderFactory;
import io.ltr8.tson.schema.meta.Data;
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
    public TsonTypeReader<?> reader(String name) {
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
     * declares, paired with its instance reader and its factory.
     *
     * <p><b>A constructor with no factory is kept, not dropped</b>, its factory standing in as an
     * {@link ErrorReader} that carries the real cause. Dropping it silently -- which this did -- lost the
     * constructor from the vocabulary without complaint, so a governing meta compiled and registered
     * looking healthy while missing a constructor it declares, and the failure surfaced against a
     * <em>different</em> document: the first governed schema to write {@code !C ...} was told
     * "'C' is not a constructor 'this-meta' declares", which is both false and unactionable. Keeping it
     * puts the verdict where the gap is -- the entry that could not be built -- and states what is
     * missing.
     *
     * <p>The two facets fail independently and that is the point: the <em>instance</em> reader (reading
     * {@code !C value} while resolving a governed schema) is compiled from {@code C}'s own record-shaped
     * declaration and usually works, while the <em>factory</em> (building a reader for a governed entry
     * whose body <em>is</em> such a construction) needs library support for {@code C}. A governed schema
     * that merely declares such an entry still compiles; only reading a value against it fails -- the same
     * treatment {@code extern}/{@code unknown_type} already get, and the reason those register an
     * {@code ErrorReader} factory rather than throwing.
     */
    private static Map<String, ReaderResolver> buildConstructors(
            TsonCompiledSchema compiledSchema, ValueReaderFactoryResolver resolver) {
        Map<String, ReaderResolver> constructors = new HashMap<>();
        for (Map.Entry<String, TypeDefinition> entry : compiledSchema.schema().entries().entrySet()) {
            // Applicability is IS-A `top` ([TSON-SCHEMA] §4.1), the same predicate `DefinitionResolver`'s
            // own gate asks -- so a head that gate admits has a reader here, and one it refuses never
            // reaches this table. Wider than `constructor`, by the base kinds and `reference`; narrower
            // than the whole namespace, by every component record (`record_field`, `type_ref`, …), which
            // is a part of a type and not one.
            if (!entry.getValue().supertypes().contains("top")) {
                continue;
            }
            String name = entry.getKey();
            // Already extension-substituted where one was needed -- see withExtensionReaders.
            TsonTypeReader<?> instanceReader = compiledSchema.find(name).orElse(null);
            if (instanceReader == null) {
                continue;
            }
            ValueReaderFactory factory;
            try {
                factory = resolver.resolve(name);
            } catch (RuntimeException noFactory) {
                factory = unbuildable(compiledSchema.schema().id(), name, noFactory);
            }
            constructors.put(name, new ReaderResolver(instanceReader, factory));
        }
        return Map.copyOf(constructors);
    }

    /**
     * The stand-in factory for a constructor this library cannot build a reader for. It names the
     * constructor, the meta-schema that declares it and the entry that tried to use it, so the message
     * identifies the gap rather than the document that ran into it.
     */
    private static ValueReaderFactory unbuildable(String metaId, String constructorName, RuntimeException cause) {
        return (entryName, definition, context) -> new ErrorReader(entryName,
                new UnsupportedOperationException("'" + entryName + "' is built with '" + constructorName
                        + "', a constructor declared by the meta-schema '" + metaId + "' that this library has "
                        + "no reader for: " + cause.getMessage(), cause));
    }

    /**
     * The constructor name a resolved body identifies as -- its own {@code @Typename}, e.g. {@code
     * "record"}/{@code "float_type"}. Package-private so {@link TsonSchemaCompiler} dispatches a
     * body to its factory the same way this class scopes one.
     */
    static String typenameOf(Top body) {
        Typename typename = body.getClass().getAnnotation(Typename.class);
        if (typename == null) {
            throw new IllegalStateException(body.getClass() + " has no @Typename -- every Top leaf must carry "
                    + "one, a " + Data.class.getSimpleName() + " implementation included (it names the "
                    + "constructor it is the body of)");
        }
        return typename.name();
    }

    /**
     * A declared constructor's two compiled facets, held together only while this class builds its
     * scoped vocabulary: the reader for an *instance* of it ({@link #reader}) and the factory that
     * builds a reader for an entry that *is* a construction of it ({@link #constructor}). A private
     * convenience -- neither the record nor its facet split leaks past this class.
     */
    private record ReaderResolver(TsonTypeReader<?> instanceReader, ValueReaderFactory factory) {
    }
}
