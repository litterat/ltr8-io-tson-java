package io.ltr8.tson;

import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonDiagnosticsCollector;
import io.ltr8.tson.compiler.TsonDiagnosticsReceiver;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * An annotation is checked against the governing schema wherever it is written, whether or not the reader
 * has anywhere to keep it.
 *
 * <p><b>Keeping and checking are two questions, and only the first is about the reader.</b> [TSON-SCHEMA] §6
 * makes {@code @T} name a type whose contract its value must satisfy. Whether the result has somewhere to go
 * afterwards is a fact about the bound Java class — a record that declares an {@code Annotations} component
 * or does not. A document does not become conformant because the application reading it throws the
 * annotation away, and it used to: the same document was invalid read into a class with a carrier and valid
 * read into one without.
 */
class AnnotationValidationTest {

    private static final String SCHEMA = """
            !!id:"https://example.test/annotated-1.tn"
            !!meta:"https://tson.io/2026/33/m/meta.tn"
            !!import:"https://tson.io/2026/33/m/core.tn"
            {
              level => int32
              plain => { id: text }
              carrier => { id: text }
              shape => {}
              circle => shape & { radius: text }
              holder => { thing: shape }
            }
            """;

    /** No {@code Annotations} component: this class throws every annotation away. */
    public record Plain(String id) {
    }

    /** The same record, with somewhere to put them. */
    public record Carrier(String id, Annotations annotations) {
    }

    public sealed interface Shape permits Circle {
    }

    @Typename(name = "circle")
    public record Circle(String radius) implements Shape {
    }

    public record Holder(Shape thing) {
    }

    private static Tson tson() {
        Map<String, Class<?>> names = Map.of("plain", Plain.class, "carrier", Carrier.class,
                "shape", Shape.class, "circle", Circle.class, "holder", Holder.class);
        DataNameBinder binder = n -> names.containsKey(n) ? names.get(n) : SchemaMetaNameBinder.INSTANCE.resolve(n);
        return Tson.builder().schemaSource((TsonSchemaSource) uri -> SCHEMA)
                .dataBindContext(TsonAtomContext.registerDefaults(
                        DataBindContext.builder().nameBinder(binder).build()))
                .build();
    }

    private static List<Diagnostic> binding(String document, Class<?> type) {
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        tson().objectReader().withDiagnostics(problems).read(document, type);
        return problems.diagnostics();
    }

    private static String document(String annotation, String type) {
        return """
                !!schema:"https://example.test/annotated-1.tn"
                %s
                !%s { id: "1" }""".formatted(annotation, type);
    }

    private static Diagnostic only(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), problems.toString());
        return problems.getFirst();
    }

    /**
     * The motivating asymmetry, in one test: the carrier decided the verdict. `Plain` and `Carrier` differ
     * in nothing a document can see.
     */
    @Test
    void aNameTheSchemaDoesNotDeclareIsReportedWhoeverReadsIt() {
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF,
                only(tson().validate(document("@nosuchtype:\"x\"", "plain"))).code());
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF,
                only(binding(document("@nosuchtype:\"x\"", "carrier"), Carrier.class)).code());
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF,
                only(binding(document("@nosuchtype:\"x\"", "plain"), Plain.class)).code());
    }

    /** And the value, not just the name: {@code @T:v} means v satisfies T, wherever v ends up. */
    @Test
    void aValueTheAnnotationsTypeRejectsIsReportedWhoeverReadsIt() {
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                only(tson().validate(document("@level:\"not-an-int\"", "plain"))).code());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                only(binding(document("@level:\"not-an-int\"", "carrier"), Carrier.class)).code());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                only(binding(document("@level:\"not-an-int\"", "plain"), Plain.class)).code());
    }

    /**
     * A document whose annotations are fine still reads, and the class without a carrier still keeps none of
     * them. Checking what is dropped changes the verdict on a bad document, not the value built from a good
     * one.
     */
    @Test
    void aGoodAnnotationIsStillDroppedSilentlyByAClassWithNowhereToPutIt() {
        String document = document("@level:3", "plain");

        assertEquals(List.of(), binding(document, Plain.class));
        assertEquals(new Plain("1"), tson().objectReader().read(document, Plain.class));
    }

    /**
     * Bind mode is all-or-nothing, so an annotation this reader was going to discard anyway can now fail the
     * whole read. That is the point: the document is invalid, and it was being accepted because of a
     * property of the class rather than of itself.
     */
    @Test
    void aDocumentWithABadAnnotationNoLongerBindsJustBecauseTheClassIgnoresIt() {
        assertNull(tson().objectReader().withDiagnostics(TsonDiagnosticsReceiver.collecting())
                .read(document("@nosuchtype:\"x\"", "plain"), Plain.class));
        assertNotNull(tson().objectReader().read(document("@level:3", "plain"), Plain.class));
    }

    /**
     * A dispatched position too, where the annotations reach no reader at all: the variant's own class has
     * no carrier and the dispatcher drops them, but the schema still gets to reject one it does not declare.
     * (That the drop happens at all, even for a variant class that <em>does</em> declare a carrier, is the
     * separate defect tracked in {@code BACKLOG.md}.)
     */
    @Test
    void aDispatchedValuesAnnotationsAreCheckedThoughNoReaderKeepsThem() {
        String document = """
                !!schema:"https://example.test/annotated-1.tn"
                !holder { thing: @nosuchtype:"x" !circle { radius: "3" } }""";

        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF, only(binding(document, Holder.class)).code());
    }

    /**
     * A schemaless read is unchanged: with no governing schema there is nothing to check against, and §3.1
     * keeps an annotation as authored without making any claim about its type.
     */
    @Test
    void aSchemalessReadStillMakesNoClaimAboutAnAnnotationsType() {
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        Plain value = new TsonObjectReaderFixture().read(problems);

        assertEquals(List.of(), problems.diagnostics());
        assertEquals(new Plain("1"), value);
    }

    /** A standalone (schemaless) object reader over the same document, with no schema in scope at all. */
    private static final class TsonObjectReaderFixture {
        Plain read(TsonDiagnosticsCollector problems) {
            return new io.ltr8.tson.compiler.TsonObjectReader()
                    .withDiagnostics(problems)
                    .read("@nosuchtype:\"x\" { id: \"1\" }", Plain.class);
        }
    }
}
