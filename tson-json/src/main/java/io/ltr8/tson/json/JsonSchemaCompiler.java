package io.ltr8.tson.json;

import io.ltr8.tson.json.reader.*;
import io.ltr8.tson.json.reader.ValueReaderFactory;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.MissingBindingException;
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
 * Turns a linked schema into a {@link JsonCompiledSchema} -- one {@link JsonTypeReader} per entry, wired as
 * real Java references, <b>eagerly</b>, so a broken entry surfaces at compile time rather than on the first
 * document that reaches it.
 *
 * <p><b>The compile is where the position is decided.</b> [TSON-JSON] §4.1 requires that where two readings
 * could apply to one JSON form, a stated rule select one <em>before</em> the value's content is touched.
 * Deciding per value would be a per-document cost and a per-document opportunity to differ; deciding once per
 * entry makes the wire decision a reference hop, which is what §8.3 asks for in so many words.
 *
 * <p><b>What it consumes is a value model.</b> {@link TsonLinkedSchema} is a {@code tson-schema} record, and
 * the resolution, linking and registration that produce it are encoding-neutral and stay in {@code
 * tson-compiler}. Nothing here names a type from that engine.
 *
 * <p><b>A failure building one entry becomes a {@link ErrorReader}</b> rather than failing the compile:
 * the schema compiles, and reading a value against that one type reports {@code NOT_IMPLEMENTED} and skips
 * it. That is what keeps a constructor this encoding cannot read -- one a meta-schema other than the bundled
 * ones declares ([TSON-SCHEMA] §2.2.2) -- from costing a document every other verdict it was owed.
 *
 * <p><b>A foreign schema is not compiled here.</b> §8.5's scope push names a schema from inside the document, so
 * a compile is handed {@link ForeignSchemas}, where to ask for one when a value arrives. A compile with no
 * registry behind it passes {@link ForeignSchemas#none()}.
 */
public final class JsonSchemaCompiler {

    private JsonSchemaCompiler() {
    }

    /** Compiles every entry in tree mode, with no foreign schema reachable; unreadable constructors become gaps. */
    public static JsonCompiledSchema compile(TsonLinkedSchema linkedSchema) {
        return compile(linkedSchema, ValueReaderFactoryRegistry.tree());
    }

    /** {@link #compile(TsonLinkedSchema, ValueReaderFactoryResolver, ForeignSchemas)} with no foreign schema. */
    public static JsonCompiledSchema compile(TsonLinkedSchema linkedSchema,
                                             ValueReaderFactoryResolver factories) {
        return compile(linkedSchema, factories, ForeignSchemas.none());
    }

    /**
     * Compiles every entry, dispatching each resolved body to {@code factories} by its constructor name, with a
     * scoped position's foreign schemas looked up through {@code foreign}.
     */
    public static JsonCompiledSchema compile(TsonLinkedSchema linkedSchema, ValueReaderFactoryResolver factories,
                                             ForeignSchemas foreign) {
        Compilation compilation = new Compilation(linkedSchema, factories, foreign);
        for (String name : linkedSchema.schema().entries().keySet()) {
            compilation.resolve(name);
        }
        JsonCompiledSchema compiled = new JsonCompiledSchema(linkedSchema, compilation.finished);
        compilation.readers.bind(compiled);
        return compiled;
    }

    /** One compile's own state: what is finished, what is in flight, and the factories it dispatches through. */
    private static final class Compilation {

        private final TsonLinkedSchema linked;
        private final TsonSchema schema;
        private final ValueReaderFactoryResolver factories;
        private final ForeignSchemas foreign;
        private final Map<String, JsonTypeReader<?>> finished = new LinkedHashMap<>();
        private final Set<String> building = new LinkedHashSet<>();

        /** How a factory reaches another entry's reader -- rebound once, when this ends. */
        private final CompiledReaders readers = new CompiledReaders(this::resolve);

        Compilation(TsonLinkedSchema linked, ValueReaderFactoryResolver factories, ForeignSchemas foreign) {
            this.linked = linked;
            this.schema = linked.schema();
            this.factories = factories;
            this.foreign = foreign;
        }

        JsonTypeReader<?> resolve(String name) {
            JsonTypeReader<?> done = finished.get(name);
            if (done != null) {
                return done;
            }
            if (!building.add(name)) {
                return new DeferredTypeReader(name, finished);
            }
            try {
                TypeDefinition definition = schema.entries().get(name);
                if (definition == null) {
                    throw new IllegalStateException("'" + name + "' is referenced but not present in the schema -- "
                            + "linking should already have rejected this before compilation ever started");
                }
                JsonTypeReader<?> built;
                try {
                    built = build(name, definition);
                } catch (MissingBindingException e) {
                    // A type with no class at all is deferred, not fatal: a schema declares types a given consumer
                    // never binds, and failing the compile for those would make bind mode unusable. It reaches
                    // the first read of this type still saying what it is (ErrorReader rethrows it).
                    built = new ErrorReader(name, e);
                } catch (BindMismatchException e) {
                    // Not a gap: a wiring mistake between the schema and the caller's own classes, and deferring
                    // it to the first document that happens to have this type is what makes it expensive to find.
                    throw e;
                } catch (RuntimeException e) {
                    built = new ErrorReader(name, e);
                }
                finished.put(name, built);
                return built;
            } finally {
                building.remove(name);
            }
        }

        private JsonTypeReader<?> build(String name, TypeDefinition definition) {
            ValueReaderContext context = new ValueReaderContext(linked, readers, foreign);
            Top body = definition.body();
            if (body instanceof Reference reference) {
                // [TSON-SCHEMA] §8.3: a processor MAY collapse a reference chain when it compiles for
                // reading, and this is that moment -- the reader is its target's, reached once here rather
                // than walked on every value.
                return resolve(reference.target().name());
            }
            ValueReaderFactory factory = factories.resolve(constructorOf(body));
            return factory.create(name, definition, context);
        }
    }

    /** Which constructor a resolved body is an instance of -- the name every {@code Top} leaf carries. */
    private static String constructorOf(Top body) {
        Typename typename = body.getClass().getAnnotation(Typename.class);
        if (typename == null) {
            throw new IllegalStateException(body.getClass() + " has no @Typename -- every Top leaf must carry one");
        }
        return typename.name();
    }
}
