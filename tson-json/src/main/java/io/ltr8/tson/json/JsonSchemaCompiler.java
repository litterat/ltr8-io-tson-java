package io.ltr8.tson.json;

import io.ltr8.tson.json.reader.JsonDeferredReader;
import io.ltr8.tson.json.reader.JsonErrorReader;
import io.ltr8.tson.json.reader.JsonOpenTemplateReader;
import io.ltr8.tson.json.reader.JsonValueReaderContext;
import io.ltr8.tson.json.reader.JsonValueReaderFactory;
import io.ltr8.tson.json.reader.JsonValueReaderFactoryRegistry;
import io.ltr8.tson.json.reader.JsonValueReaderFactoryResolver;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;

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
 * <p><b>A failure building one entry becomes a {@link JsonErrorReader}</b> rather than failing the compile:
 * the schema compiles, and reading a value against that one type reports {@code NOT_IMPLEMENTED} and skips
 * it. That is what keeps §6-§8's absence from costing a document every other verdict it was owed.
 */
public final class JsonSchemaCompiler {

    private JsonSchemaCompiler() {
    }

    /** Compiles every entry with [TSON-JSON] §5's atom vocabulary; §6-§8's constructors become gaps. */
    public static JsonCompiledSchema compile(TsonLinkedSchema linkedSchema) {
        return compile(linkedSchema, JsonValueReaderFactoryRegistry.atoms());
    }

    /** Compiles every entry, dispatching each resolved body to {@code factories} by its constructor name. */
    public static JsonCompiledSchema compile(TsonLinkedSchema linkedSchema,
                                             JsonValueReaderFactoryResolver factories) {
        Compilation compilation = new Compilation(linkedSchema, factories);
        for (String name : linkedSchema.schema().entries().keySet()) {
            compilation.resolve(name);
        }
        return new JsonCompiledSchema(linkedSchema, compilation.finished);
    }

    /** One compile's own state: what is finished, what is in flight, and the factories it dispatches through. */
    private static final class Compilation {

        private final TsonLinkedSchema linked;
        private final TsonSchema schema;
        private final JsonValueReaderFactoryResolver factories;
        private final Map<String, JsonTypeReader<?>> finished = new LinkedHashMap<>();
        private final Set<String> building = new LinkedHashSet<>();

        Compilation(TsonLinkedSchema linked, JsonValueReaderFactoryResolver factories) {
            this.linked = linked;
            this.schema = linked.schema();
            this.factories = factories;
        }

        JsonTypeReader<?> resolve(String name) {
            JsonTypeReader<?> done = finished.get(name);
            if (done != null) {
                return done;
            }
            if (!building.add(name)) {
                return new JsonDeferredReader(name, finished);
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
                } catch (RuntimeException e) {
                    built = new JsonErrorReader(name, e);
                }
                finished.put(name, built);
                return built;
            } finally {
                building.remove(name);
            }
        }

        private JsonTypeReader<?> build(String name, TypeDefinition definition) {
            JsonValueReaderContext context = new JsonValueReaderContext(linked, this::resolve);
            if (definition.kind() == TypeKind.TEMPLATE) {
                // Before the body is looked at at all: a template's body is held unsubstituted text, so no
                // factory could read it, and a template is not a type until it is applied ([TSON-SCHEMA] §5.10).
                return new JsonOpenTemplateReader(name, definition.parameters(),
                        context.locationOf(name, definition));
            }
            Top body = definition.body();
            if (body instanceof Reference reference) {
                // [TSON-SCHEMA] §8.3: a processor MAY collapse a reference chain when it compiles for
                // reading, and this is that moment -- the reader is its target's, reached once here rather
                // than walked on every value.
                return resolve(reference.target().name());
            }
            JsonValueReaderFactory factory = factories.resolve(constructorOf(body));
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
