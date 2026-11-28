package io.ltr8.tson.compiler;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.compiler.reader.ErrorReader;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryResolver;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.compiler.reader.ValueReaderFactory;
import io.ltr8.tson.schema.meta.Top;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A schema compiled in object-binding mode as <b>vocabulary</b>: its entries, and the reader for an <em>instance</em>
 * of each constructor it declares or imports ({@link #reader}). The kernel's pre-loaded stand-in is one, which the
 * kernel's own resolution reads every head through ([TSON-SCHEMA] §3.4); a resolution's checkpoints are others
 * ({@link TsonCompiledVocabularyRegistry#compileReady}). Every entry IS-A {@code top}, keyed by its own name. The
 * factory that builds a reader for an entry whose body <em>is</em> such a construction is the library's global set,
 * consulted by {@link #compile}.
 */
public final class TsonCompiledVocabulary extends TsonCompiledSchema {

    private final Map<String, TsonTypeReader<?>> constructors;

    private TsonCompiledVocabulary(TsonCompiledSchema base) {
        super(base.linkedSchema(), base.entries(), null, null, base.foreignSchemas());
        this.constructors = buildConstructors(this);
    }

    /**
     * Compiles {@code linked} through {@code resolver}'s factories as vocabulary. Every body is dispatched to the
     * factory for its class's constructor; a body no factory backs stands in as an {@link ErrorReader} ({@link
     * #unbuildable}), so the schema compiles and only reading a value against that entry fails.
     */
    static TsonCompiledVocabulary compile(TsonLinkedSchema linked, ValueReaderFactoryResolver resolver) {
        return compileWith(linked, resolver, Set.of(), false, null);
    }

    /**
     * {@link #compile} of a schema whose resolution is in progress, {@code pending} naming its declarations not yet
     * ready ({@link TsonSchemaCompiler#compileReady}), a schema an extern names compiled by {@code foreign}.
     */
    static TsonCompiledVocabulary compileReady(TsonLinkedSchema linked, ValueReaderFactoryResolver resolver,
                                               Set<String> pending, TsonSchemaCompiler.ForeignSchemas foreign) {
        return compileWith(linked, resolver, pending, true, foreign);
    }

    private static TsonCompiledVocabulary compileWith(TsonLinkedSchema linked, ValueReaderFactoryResolver resolver,
                                                      Set<String> pending, boolean checkpoint,
                                                      TsonSchemaCompiler.ForeignSchemas foreign) {
        return new TsonCompiledVocabulary(TsonSchemaCompiler.compileWith(linked, name -> {
            try {
                return resolver.resolve(name);
            } catch (RuntimeException noFactory) {
                return unbuildable(name, noFactory);
            }
        }, pending, checkpoint, foreign, TsonSchemaCompiler.bindingOf(resolver), false));
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
     * declaration's own name (e.g. {@code "meta.record"}), not necessarily its resolved body's own constructor name
     * (though for every kernel declaration the two are identical).
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
     * never reaches this table. Wider than {@code constructor}, by the base kinds and {@code alias}; narrower than the
     * whole namespace, by every component record ({@code meta.record_field}, {@code meta.param}, …), which is
     * a part of a type and not one.
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
            constructors.put(name, instanceReader);
        }
        return Map.copyOf(constructors);
    }

    /**
     * The stand-in factory for a body no factory backs. Dispatch is by the body's class, and an instance of a schema's
     * own constructor never becomes one of the schema model's bodies (it is refused, or held as a value), so what
     * reaches this is an application a checkpoint compiles before materialisation closes it ({@code meta.apply});
     * the finished schema holds none.
     */
    private static ValueReaderFactory unbuildable(String constructorName, RuntimeException cause) {
        return (entryName, definition, context) -> new ErrorReader(entryName,
                new UnsupportedOperationException("'" + entryName + "' holds a '" + constructorName + "' body, which "
                        + "no reader factory backs: " + cause.getMessage(), cause));
    }

    /**
     * The constructor name a resolved body identifies as -- its own {@code @Typename}, e.g. {@code "meta.record"}/{@code
     * "numeric.float_type"}. Package-private so {@link TsonSchemaCompiler} dispatches a body to its factory the same way
     * this class scopes one. A value entry is built by no constructor and is never dispatched.
     */
    static String typenameOf(Top body) {
        Typename typename = body.getClass().getAnnotation(Typename.class);
        if (typename == null) {
            throw new IllegalStateException(body.getClass() + " has no @Typename -- every body a constructor "
                    + "builds carries one, naming the constructor it is the body of");
        }
        return typename.name();
    }
}
