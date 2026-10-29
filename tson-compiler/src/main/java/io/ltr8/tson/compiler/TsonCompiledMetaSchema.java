package io.ltr8.tson.compiler;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.compiler.reader.ErrorReader;
import io.ltr8.tson.compiler.reader.NamespaceInstanceReader;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryResolver;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.compiler.reader.ValueReaderFactory;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.Top;

import java.util.HashMap;
import java.util.Map;

/**
 * A schema compiled in object-binding mode as <b>vocabulary</b>: what a schema importing it reads its construction
 * heads and annotation values through. One namespace per schema ([TSON-SCHEMA] §3.3.1, SPEC-FEEDBACK.md #7) puts
 * every constructor of the import closure in reach, and the resolver reads {@code !C value} through the compiled form
 * of the schema that declared {@code C} ({@code SchemaResolver}'s vocabulary), which is one of these. Any schema
 * compiles to one -- the kernel, meta.tn, a library declaring constructors of its own -- and {@link
 * TsonCompiledMetaRegistry#loadMeta} caches each by identity.
 *
 * <p>Holds, beside the compiled entries, the reader for an <em>instance</em> of each constructor it declares or
 * imports ({@link #reader}): every entry IS-A {@code top}, keyed by its own name. The factory that builds a reader for
 * an entry whose body <em>is</em> such a construction is the library's global set, consulted by {@link #compile}.
 */
public final class TsonCompiledMetaSchema extends TsonCompiledSchema {

    private final Map<String, TsonTypeReader<?>> constructors;

    private TsonCompiledMetaSchema(TsonCompiledSchema base) {
        super(base.linkedSchema(), base.entries());
        this.constructors = buildConstructors(this);
    }

    /**
     * Compiles {@code linked} through {@code resolver}'s factories as vocabulary. Every body is dispatched to the
     * factory for its constructor; a constructor this library has no factory for -- one a schema declared, composing
     * {@code atom} or {@code top} -- stands in as an {@link ErrorReader} naming the gap ({@link #unbuildable}), so the
     * schema compiles and only reading a value against that entry fails.
     */
    static TsonCompiledMetaSchema compile(TsonLinkedSchema linked, ValueReaderFactoryResolver resolver) {
        return new TsonCompiledMetaSchema(TsonSchemaCompiler.compile(linked, name -> {
            try {
                return resolver.resolve(name);
            } catch (RuntimeException noFactory) {
                return unbuildable(linked.originOf(name), name, noFactory);
            }
        }));
    }

    /**
     * This schema as a plain {@link TsonCompiledSchema} -- it *is* one (this class extends it), so this returns
     * {@code this}. Retained so a caller reading an arbitrary entry ({@code compiledSchema().get(name)}) keeps a
     * stable spelling; {@link #reader} is the constructor-only view.
     */
    public TsonCompiledSchema compiledSchema() {
        return this;
    }

    /**
     * Reads an instance of {@code name}, a constructor in this schema's namespace -- {@code name} is the
     * declaration's own name (e.g. {@code "record"}), not necessarily its resolved body's own constructor name
     * (though for every meta-kernel/meta.tn declaration the two are identical). An instance of a namespace type is
     * the map it reads as, delivered as a {@link Namespace}.
     */
    public TsonTypeReader<?> reader(String name) {
        TsonTypeReader<?> constructor = constructors.get(name);
        if (constructor == null) {
            throw new IllegalArgumentException(
                    "'" + name + "' is not a constructor in the namespace of '" + schema().id() + "'");
        }
        return constructor;
    }

    /**
     * Every entry IS-A {@code top} ([TSON-SCHEMA] §4.1), with the reader for an instance of it -- the same predicate
     * {@code DefinitionResolver}'s own gate asks, so a head that gate admits has a reader here, and one it refuses
     * never reaches this table. Wider than {@code constructor}, by the base kinds and {@code reference}; narrower than
     * the whole namespace, by every component record ({@code record_field}, {@code type_ref}, …), which is a part of
     * a type and not one.
     */
    private static Map<String, TsonTypeReader<?>> buildConstructors(TsonCompiledSchema compiledSchema) {
        Map<String, TsonTypeReader<?>> constructors = new HashMap<>();
        for (Map.Entry<String, Top> entry : compiledSchema.schema().entries().entrySet()) {
            if (!compiledSchema.linkedSchema().isConstructor(entry.getKey(), entry.getValue())) {
                continue;
            }
            String name = entry.getKey();
            TsonTypeReader<?> instanceReader = compiledSchema.find(name).orElse(null);
            if (instanceReader == null) {
                continue;
            }
            if (compiledSchema.linkedSchema().namespace(name).isPresent()) {
                // A namespace type reads as the map its facets describe; an instance of it, `!api { ... }`, is
                // that map as a value of the constructor.
                instanceReader = new NamespaceInstanceReader(name, instanceReader);
            }
            constructors.put(name, instanceReader);
        }
        return Map.copyOf(constructors);
    }

    /**
     * The stand-in factory for a constructor this library cannot build a reader for. It names the constructor, the
     * schema that declares it and the entry that tried to use it, so the message identifies the gap rather than the
     * document that ran into it. Kept rather than dropped: an entry built with an unbacked constructor still
     * compiles, and the verdict lands where the gap is, on reading a value against it.
     */
    private static ValueReaderFactory unbuildable(String declaredBy, String constructorName, RuntimeException cause) {
        return (entryName, definition, context) -> new ErrorReader(entryName,
                new UnsupportedOperationException("'" + entryName + "' is built with '" + constructorName
                        + "', a constructor declared by '" + declaredBy + "' that this library has "
                        + "no reader for: " + cause.getMessage(), cause));
    }

    /**
     * The constructor name a resolved body identifies as -- its own {@code @Typename}, e.g. {@code
     * "record"}/{@code "float_type"}, and a {@link Namespace}'s {@code type}, the constructor it is an instance of.
     * Package-private so {@link TsonSchemaCompiler} dispatches a body to its factory the same way this class scopes
     * one.
     */
    static String typenameOf(Top body) {
        if (body instanceof Namespace<?> value) {
            return value.type();
        }
        Typename typename = body.getClass().getAnnotation(Typename.class);
        if (typename == null) {
            throw new IllegalStateException(body.getClass() + " has no @Typename -- every Top leaf but a "
                    + "Namespace carries one, naming the constructor it is the body of");
        }
        return typename.name();
    }
}
