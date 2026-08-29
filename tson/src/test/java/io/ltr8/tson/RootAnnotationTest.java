package io.ltr8.tson;

import io.ltr8.annotation.Annotations;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.tree.TsonValue;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A schema-governed document's root value may carry annotations, and they sit <em>before</em> its type-ref:
 * [TSON-DATA]'s data-value is {@code *annotation [type-ref] core-value} and §3.3 has augmentation attaching
 * to the value that follows it, so {@code @doc:"..." !api { ... }} annotates and types one value.
 *
 * <p><b>Why this matters more than it looks.</b> TSON has no comment syntax (§2.4, deliberately), so an
 * annotation is the only way to put prose in a document. A root that cannot carry one leaves a
 * schema-governed document unable to say what it is for -- and configuration, fixtures and API descriptions
 * are exactly the documents that want to.
 *
 * <p>The reader stack has always handled this; what could not was the lookup that finds the root type-ref to
 * select a reader <em>with</em>, which saw one event and concluded a type-ref that was there was missing.
 */
class RootAnnotationTest {

    private static final String ID = "https://example.test/api-2.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/api-2.tn"
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
            {
              api => { name: text }
              since => text
              owner => { team: text  contact: text }
              deprecated => void
            }
            """;

    private static Tson tson() {
        return Tson.builder().schemaSource((TsonSchemaSource) uri -> SCHEMA).build();
    }

    private static TsonValue tree(String document) {
        return tson().treeReader().read(document);
    }

    /** The motivating document: prose explaining why it exists, above the type-ref that types it. */
    @Test
    void anAnnotationBeforeTheRootTypeRefDoesNotHideIt() {
        String document = """
                !!schema:"https://example.test/api-2.tn"
                @doc:"why this document exists"
                !api { name: "orders" }""";

        assertEquals(List.of(), tson().validate(document));

        TsonValue root = tree(document);
        assertEquals("api", root.typeRef().orElseThrow());
        assertEquals("orders", root.get("name").asString().orElseThrow());
        assertEquals("why this document exists",
                root.annotations().getFirst().value().orElseThrow().asString().orElseThrow());
    }

    /**
     * Every annotation, not just one, and whatever its value's shape -- the lookahead reads the run by the
     * same rule the readers do rather than a special case for the common spelling.
     */
    @Test
    void aWholeRunOfAnnotationsIsLookedPast() {
        String document = """
                !!schema:"https://example.test/api-2.tn"
                @doc:\"""
                  a multi-line explanation
                  of this document
                  \"""
                @since:"2026-08"
                @owner:{ team: "platform"  contact: "ops@example.test" }
                @deprecated
                !api { name: "orders" }""";

        assertEquals(List.of(), tson().validate(document));

        TsonValue root = tree(document);
        assertEquals("api", root.typeRef().orElseThrow());
        assertEquals(List.of("doc", "since", "owner", "deprecated"),
                root.annotations().stream().map(a -> a.name()).toList());
        assertTrue(root.annotations().getLast().value().isEmpty(), "a valueless annotation is looked past too");
    }

    /**
     * The annotations survive the lookahead. They are rewound rather than consumed precisely so the reader
     * that builds the value still sees them -- a lookahead that ate them would select the right reader and
     * hand it a value stripped of the prose this exists to allow.
     */
    @Test
    void theAnnotationsReachTheBoundObjectToo() {
        String document = """
                !!schema:"https://example.test/api-2.tn"
                @doc:"why this document exists"
                !api { name: "orders" }""";

        DataNameBinder binder = name -> "api".equals(name) ? Api.class : SchemaMetaNameBinder.INSTANCE.resolve(name);
        Tson tson = Tson.builder().schemaSource((TsonSchemaSource) uri -> SCHEMA)
                .dataBindContext(TsonAtomContext.registerDefaults(
                        DataBindContext.builder().nameBinder(binder).build()))
                .build();

        Api api = tson.objectReader().read(document, Api.class);

        assertEquals("orders", api.name());
        assertEquals("why this document exists",
                api.annotations().get("doc").orElseThrow().value().orElseThrow());
    }

    /** A class carrying the wire annotations alongside its own fields. */
    public record Api(String name, Annotations annotations) {
    }

    /**
     * A document that genuinely has no type-ref still says so, and now says it truthfully -- the complaint
     * was never that the message was unclear, but that it was made about a document that had one.
     */
    @Test
    void aRootThatReallyHasNoTypeRefStillReportsOne() {
        List<Diagnostic> problems = tson().validate("""
                !!schema:"https://example.test/api-2.tn"
                @doc:"annotated, but never typed"
                { name: "orders" }""");

        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, problems.getFirst().code());
        assertTrue(problems.getFirst().message().contains("no root type-ref"), problems.getFirst().message());
    }

    /**
     * Looking ahead leaves the read where it started, so what follows is validated as it always was -- the
     * point of rewinding rather than consuming, checked against the thing it would break.
     */
    @Test
    void theRewoundStreamStillValidatesWhatFollows() {
        List<Diagnostic> problems = tson().validate("""
                !!schema:"https://example.test/api-2.tn"
                @doc:"this document is wrong, and says so below"
                !api { name: "orders"  nope: 1 }""");

        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, problems.getFirst().code());
        assertEquals("/nope", problems.getFirst().path().orElseThrow());
    }

    /**
     * Trailing content is still rejected, which is the other thing a rewound stream could quietly break:
     * {@code requireDocumentEnd}'s pull is what finds it, and a buffer that swallowed the end of the
     * document would leave nothing to pull.
     */
    @Test
    void trailingContentAfterAnAnnotatedRootIsStillRejected() {
        List<Diagnostic> problems = tson().validate("""
                !!schema:"https://example.test/api-2.tn"
                @doc:"why"
                !api { name: "orders" } junk""");

        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, problems.getFirst().code());
    }

    /**
     * The other spelling stays a syntax error, and should: {@code *annotation [type-ref] core-value} puts
     * annotations before the type-ref, so an annotation after one is not a document this grammar admits.
     * The fix is that the grammatical spelling now works, not that both do.
     */
    @Test
    void anAnnotationAfterTheTypeRefIsStillASyntaxError() {
        List<Diagnostic> problems = tson().validate("""
                !!schema:"https://example.test/api-2.tn"
                !api @doc:"why" { name: "orders" }""");

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().message().contains("found '@'"), problems.getFirst().message());
    }
}
