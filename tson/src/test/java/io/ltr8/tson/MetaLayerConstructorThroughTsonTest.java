package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.TsonMissingBindingException;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.consumer.Operation;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A consumer's own meta-layer constructor, reached through the front door. {@code
 * MetaLayerDataConstructorTest} pins the mechanism itself against a hand-built {@code
 * TsonCompiledMetaRegistry}; this pins that a caller gets it from {@link Tson#builder()} without giving up
 * the reader, the writer or the per-mode registries that come with a {@link Tson}.
 *
 * <p>{@link TsonConfig#metaNameBinder} is the whole difference. It is composed over {@link
 * SchemaMetaNameBinder#INSTANCE} rather than replacing it, so what it changes is which names resolve and
 * nothing else -- the standard library still compiles in object-binding mode, which is the thing the
 * internal context is fixed to protect.
 */
class MetaLayerConstructorThroughTsonTest {

    private static final String META_HTTP = "https://example.test/meta-http.tn";
    private static final String API = "https://example.test/api.tn";

    /** The meta-layer schema. {@code ~data &}: an operation describes an endpoint, not a data value. */
    private static final String META_HTTP_SCHEMA = """
            !!id:"https://example.test/meta-http.tn"
            !!meta:"https://tson.io/2026/32/m/meta-kernel.tn"
            !!import:"https://tson.io/2026/32/m/meta.tn"
            {
              operation => ~data & {
                path:     text
                method:   text
                request:  type_ref
                response: type_ref
              }
            }
            """;

    /** An ordinary API schema governed by it -- two real types, and one entry that is not a type at all. */
    private static final String API_SCHEMA = """
            !!id:"https://example.test/api.tn"
            !!meta:"https://example.test/meta-http.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            {
              search_request  => { q: text }
              search_response => { hits: [text] }
              search => !operation {
                path: "/search"  method: "GET"  request: search_request  response: search_response
              }
            }
            """;

    private static final Map<String, String> DOCUMENTS = Map.of(META_HTTP, META_HTTP_SCHEMA, API, API_SCHEMA);

    private static final TsonSchemaSource SOURCE = uri -> {
        for (Map.Entry<String, String> document : DOCUMENTS.entrySet()) {
            if (TsonCanonicalIdentity.sameIdentity(uri, document.getKey())) {
                return document.getValue();
            }
        }
        throw new IllegalStateException("unexpected fetch: " + uri);
    };

    /** The consumer's half: their own classes, found by package. */
    private static final DataNameBinder CONSUMER_NAMES =
            new DataNameBinder.DefaultDataNameBinder(Set.of("io.ltr8.tson.consumer"), Map.of());

    private static Tson tson() {
        return Tson.builder().schemaSource(SOURCE).metaNameBinder(CONSUMER_NAMES).build();
    }

    /** The point of the whole exercise: the resolved body <em>is</em> the consumer's own class. */
    @Test
    void aConsumersMetaLayerConstructorBindsThroughTheBuilder() {
        TsonLinkedSchema linked = tson().resolve(API_SCHEMA);

        Operation search = assertInstanceOf(Operation.class, linked.schema().entries().get("search").body());
        assertEquals("/search", search.path());
        assertEquals("GET", search.method());
        assertEquals(TypeRef.of("search_request"), search.request());
        assertEquals(TypeKind.DATA, linked.schema().entries().get("search").kind());
    }

    /**
     * Without the binder the same schema fails at resolution -- the schema, the source and every other
     * option identical. Pins that the option is what carries the case, not something else in the wiring.
     *
     * <p>And it fails as a <b>misconfiguration</b>, not a library gap: the consumer never registered a class
     * for their own constructor, which is a line of wiring rather than something this library cannot do. The
     * distinction is not cosmetic -- it used to arrive as an {@code UnsupportedOperationException}, which a
     * downstream service mapped to a 501.
     */
    @Test
    void withoutTheBinderTheSameSchemaHasNoBoundClass() {
        Tson unextended = Tson.builder().schemaSource(SOURCE).build();

        TsonMissingBindingException thrown =
                assertThrows(TsonMissingBindingException.class, () -> unextended.resolve(API_SCHEMA));

        assertTrue(thrown.getMessage().contains("no bound Java class for 'operation'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("TsonConfig.bindings"), "it names the way to fix it: "
                + thrown.getMessage());
    }

    /**
     * The extension adds names and takes none away: the kernel's own vocabulary still resolves, so the
     * bundled standard library loads and the schema's ordinary types read as they always did. A consumer
     * binder that answered first -- or a context built from scratch -- is what this composition rules out.
     */
    @Test
    void theKernelsOwnVocabularyIsUntouchedAndOrdinaryTypesStillRead() {
        Tson tson = tson();
        tson.resolve(API_SCHEMA);

        TsonValue value = tson.treeReader().withSchema(API).readAs("{ q: \"tson\" }", "search_request");

        assertEquals("tson", value.get("q").asString().orElseThrow());
        assertEquals(List.of(), tson.validate("""
                !!schema:"https://example.test/api.tn"
                !search_response { hits: ["a" "b"] }
                """));
    }

    /**
     * A name neither binder knows fails with the consumer's own account of where it looked -- the packages
     * they configured, which is where a name the kernel does not declare was expected to be.
     */
    @Test
    void anUnknownNameReportsTheConsumersOwnSearchPath() {
        DataBindException thrown = assertThrows(DataBindException.class,
                () -> SchemaMetaNameBinder.extendedWith(CONSUMER_NAMES).resolve("no_such_constructor"));

        assertTrue(thrown.getMessage().contains("io.ltr8.tson.consumer.NoSuchConstructor"), thrown.getMessage());
    }

    /** The composed binder is a {@link DataNameBinder}, so a caller assembling a context themselves gets the same names. */
    @Test
    void theComposedBinderResolvesBothVocabularies() throws DataBindException {
        DataNameBinder binder = SchemaMetaNameBinder.extendedWith(CONSUMER_NAMES);

        assertEquals(Operation.class, binder.resolve("operation"));
        assertEquals(io.ltr8.tson.schema.meta.RecordBody.class, binder.resolve("record"));

        DataBindContext context = SchemaMetaNameBinder.contextExtendedWith(CONSUMER_NAMES);
        assertEquals(Operation.class, context.getDescriptor("operation").typeClass());
    }
}
