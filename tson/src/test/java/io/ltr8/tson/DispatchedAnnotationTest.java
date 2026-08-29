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
import io.ltr8.tson.tree.TsonValue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Annotations written on a value that something dispatched to survive the dispatch.
 *
 * <p><b>Why they did not.</b> {@code data-value = *annotation [type-ref] core-value}, so a dispatcher had to
 * consume the annotations to reach the {@code !typeName} it decides on — and the reader it then handed the
 * value to never saw them. Tree mode papered over it by re-attaching to the finished node; bind mode had no
 * equivalent, so a variant class declaring an {@code Annotations} carrier got an empty one, while the same
 * class read where nothing dispatched got the annotation. Whether a document's prose survived depended on how
 * deep the value sat.
 *
 * <p><b>Why they do now.</b> The dispatcher reads the type-ref without consuming it
 * ({@code EventSkip.typeRefAhead}, over {@code TsonReadContext.lookingAhead}), so the reader chosen is handed
 * the whole data-value and treats its annotations exactly as it would if nothing had dispatched to it. Both
 * modes agree because there is no longer anything for them to agree about.
 */
class DispatchedAnnotationTest {

    private static final String SCHEMA = """
            !!id:"https://example.test/dispatch-1.tn"
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
            {
              note => text
              shape => {}
              circle => shape & { radius: text }
              holder => { thing: shape }
              chooser => { pick: (text | integer) }
            }
            """;

    public sealed interface Shape permits Circle {
    }

    /** The dispatched-to class, declaring somewhere for the wire annotations to go. */
    @Typename(name = "circle")
    public record Circle(String radius, Annotations annotations) implements Shape {
    }

    public record Holder(Shape thing) {
    }

    private static Tson tson() {
        Map<String, Class<?>> names = Map.of("shape", Shape.class, "circle", Circle.class, "holder", Holder.class);
        DataNameBinder binder = n -> names.containsKey(n) ? names.get(n) : SchemaMetaNameBinder.INSTANCE.resolve(n);
        return Tson.builder().schemaSource((TsonSchemaSource) uri -> SCHEMA)
                .dataBindContext(TsonAtomContext.registerDefaults(
                        DataBindContext.builder().nameBinder(binder).build()))
                .build();
    }

    private static final String DISPATCHED = """
            !!schema:"https://example.test/dispatch-1.tn"
            !holder { thing: @note:"why this one" !circle { radius: "3" } }""";

    /** The defect itself: a bound variant with a carrier, dispatched to, keeping what the document wrote. */
    @Test
    void aDispatchedVariantsAnnotationsReachItsBoundCarrier() {
        Holder holder = tson().objectReader().read(DISPATCHED, Holder.class);

        Circle circle = (Circle) holder.thing();
        assertEquals("3", circle.radius());
        assertEquals("why this one", circle.annotations().get("note").orElseThrow().value().orElseThrow());
    }

    /**
     * And the same value read where nothing dispatches keeps them too -- which is the property that was
     * missing. The two readings of one class now agree.
     */
    @Test
    void theSameClassKeepsThemWhereNothingDispatched() {
        Circle circle = tson().objectReader().read("""
                !!schema:"https://example.test/dispatch-1.tn"
                @note:"why this one"
                !circle { radius: "3" }""", Circle.class);

        assertEquals("why this one", circle.annotations().get("note").orElseThrow().value().orElseThrow());
    }

    /** Tree mode still keeps them, now because the reader saw them rather than because they were put back. */
    @Test
    void treeModeStillKeepsADispatchedValuesAnnotations() {
        TsonValue thing = tson().treeReader().read(DISPATCHED).get("thing");

        assertEquals(Optional.of("circle"), thing.typeRef());
        assertEquals("why this one", thing.annotations().getFirst().value().orElseThrow().asString().orElseThrow());
    }

    /** A choice dispatches through the same reader, and its variants keep theirs. */
    @Test
    void aTaggedChoiceVariantKeepsItsAnnotations() {
        TsonValue pick = tson().treeReader().read("""
                !!schema:"https://example.test/dispatch-1.tn"
                !chooser { pick: @note:"chosen" !integer 42 }""").get("pick");

        assertEquals(Optional.of("integer"), pick.typeRef());
        assertEquals(42, pick.asInt().orElseThrow());
        assertEquals("chosen", pick.annotations().getFirst().value().orElseThrow().asString().orElseThrow());
    }

    /**
     * Untagged recovery has to look past the annotations too: it decides by the value's own §4 base-type
     * class, and the value no longer starts at the cursor -- where before, the annotations had already been
     * eaten and it did. A guard on the change rather than a case that was broken.
     */
    @Test
    void anUntaggedChoiceVariantIsStillRecoveredThroughItsAnnotations() {
        TsonValue pick = tson().treeReader().read("""
                !!schema:"https://example.test/dispatch-1.tn"
                !chooser { pick: @note:"chosen" 42 }""").get("pick");

        assertEquals(Optional.of("integer"), pick.typeRef());
        assertEquals("chosen", pick.annotations().getFirst().value().orElseThrow().asString().orElseThrow());
    }

    /**
     * A type-ref naming nothing the position admits still reports once and discards the <b>whole</b>
     * data-value. The error paths had to change with the rest: nothing consumes the framing any more, so
     * skipping only the core-value would leave the annotations in the stream and derail everything after
     * them. Another guard on the change, not a case that was broken.
     */
    @Test
    void anUnknownDispatchTargetDiscardsTheWholeValueFramingIncluded() {
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        TsonValue root = tson().treeReader().withDiagnostics(problems).read("""
                !!schema:"https://example.test/dispatch-1.tn"
                !holder { thing: @note:"why" !square { side: "3" } }""");

        assertEquals(1, problems.diagnostics().size(), problems.diagnostics().toString());
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF, problems.diagnostics().getFirst().code());
        assertNull(root.get("thing").asString().orElse(null));
    }

    /** The same, in bind mode, where the union bounds the candidates instead of the schema's subtypes. */
    @Test
    void anUnknownUnionMemberDiscardsTheWholeValueToo() {
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        tson().objectReader().withDiagnostics(problems).read("""
                !!schema:"https://example.test/dispatch-1.tn"
                !holder { thing: @note:"why" !square { side: "3" } }""", Holder.class);

        assertEquals(List.of(Diagnostic.Code.UNKNOWN_TYPE_REF),
                problems.diagnostics().stream().map(Diagnostic::code).toList());
    }
}
