package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.NamespaceBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.suite.SuiteBindings;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The references a namespace type and a reference hold are rewritten as every other body's are
 * ({@code MetaRefs}): materialisation closes an application in a namespace type's {@code key_type} and {@code
 * value_type}, and §8.2's synthetic merge renames a reference's {@code of} onto the entry it merged to.
 */
class ClosedBodyReferencesTest {

    private static final String ID = "https://example.test/closed-body-refs.tn";

    private static TsonLinkedSchema linked(String declarations, List<Diagnostic> problems) {
        String text = """
                !!id:"https://example.test/closed-body-refs.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema {
                  box => <T> { v: T }
                  %s
                }
                """.formatted(declarations);
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return text;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        return TsonCompiledVocabularyRegistry.withStandardLibrary(SuiteBindings.context(), source)
                .resolveLinked(ID, problems::add);
    }

    /** The schema linked clean: no problem reported, and its entries in hand. */
    private static TsonLinkedSchema clean(String declarations) {
        List<Diagnostic> problems = new ArrayList<>();
        TsonLinkedSchema linked = linked(declarations, problems);
        assertEquals(List.of(), problems);
        assertNotNull(linked);
        return linked;
    }

    private static Top entry(TsonLinkedSchema linked, String name) {
        return linked.schema().entries().get(name);
    }

    /** The schema compiled for reading, its source the one {@link #linked} resolves. */
    private static TsonCompiledSchema compiled(String declarations) {
        String text = """
                !!id:"https://example.test/closed-body-refs.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema {
                  box => <T> { v: T }
                  %s
                }
                """.formatted(declarations);
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return text;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        return TsonCompiledSchemaRegistry.tree(
                TsonCompiledVocabularyRegistry.withStandardLibrary(SuiteBindings.context(), source)).get(ID);
    }

    /**
     * {@code value_type: box<text>} names the record {@code box<text>} expands to, as a map type's does, so a value
     * of the namespace type reads against it. Left unclosed, the value at {@code a} was judged against the family
     * base {@code box}, which has no member to stand there.
     */
    @Disabled("Phase 2: an undeclared application of an open record closes in place")
    @Test
    void aNamespaceTypesValueTypeClosesItsApplication() {
        TsonCompiledSchema compiled = compiled("""
                api => !meta.namespace { key_type: meta.identifier  value_type: box<text> }
                use => { f: api }""");

        NamespaceBody api = assertInstanceOf(NamespaceBody.class, compiled.schema().entries().get("api"));
        assertTrue(((io.ltr8.tson.schema.meta.TypeRef) api.valueType()).arguments().isEmpty(),
                () -> "closed to an entry: " + api.valueType());
        RecordBody closed = assertInstanceOf(RecordBody.class,
                compiled.schema().entries().get(api.valueType().text()));
        assertEquals(TypeRef.of("text"), closed.fields().get("v").type());
        assertNotNull(compiled.get("use").read(TestDocuments.document("{ f: { a => { v: \"s\" } } }")));
    }

}
