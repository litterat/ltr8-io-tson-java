package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>An operand is judged at the end of its reference chain, not at the name the author wrote</b>
 * ([TSON-SCHEMA] §4.3): "the source of a refinement and every operand of a composition or subtraction MUST,
 * after following its reference chain (§8.3), be a definition whose body is a {@code !record}".
 *
 * <p>Both halves of that rule matter, and only the second was applied. A reference is the same type under
 * another name (§5.7's table), so an alias of a record has a field set to merge or tighten and MUST be
 * admitted; an alias whose chain ends at a <em>binding</em> record -- a top-level constructor application
 * (§5.6), a template instantiation (§8.2), or a choice -- is finished and admits neither operator.
 *
 * <p><b>Subtraction shares the composition operand path</b>, so it is governed by the same walk; §5.9 then
 * empties the contract index as it does for any subtraction.
 */
class OperandFollowsReferenceChainTest {

    private static String schema(String id, String declarations) {
        return """
                !!id:"https://example.test/%s.tn"
                !!meta:"%s"
                !!import:"%s"
                {
                %s}
                """.formatted(id, TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID, declarations);
    }

    private static TsonLinkedSchema resolve(String id, String declarations) {
        return Tson.standard().resolve(schema(id, declarations));
    }

    private static String refusal(String id, String declarations) {
        List<Diagnostic> diagnostics = Tson.standard().validateSchema(schema(id, declarations));
        assertFalse(diagnostics.isEmpty(), "expected a refusal, got none");
        return diagnostics.stream().map(Diagnostic::message).reduce("", (a, b) -> a + "\n" + b);
    }

    // ── Admitted: the chain ends at a vocabulary body ────────────────────

    /**
     * {@code ali} is {@code rec} under another name, so it has {@code rec}'s fields to contribute. The
     * transitive index reaches the terminal as well as the written name: without {@code rec} there, a field
     * typed {@code rec} would refuse a {@code sub} value, though {@code sub} IS-A a renaming of {@code rec}.
     */
    @Test
    void aCompositionThroughAnAliasToARecordIsAdmitted() {
        TsonLinkedSchema linked = resolve("ofc1", """
                  rec => { x: int32 }
                  ali => rec
                  sub => ali & { extra: text }
                """);

        List<String> supertypes = linked.schema().entries().get("sub").supertypes();
        assertTrue(supertypes.contains("ali"), () -> "the name the author wrote: " + supertypes);
        assertTrue(supertypes.contains("rec"), () -> "and the type at the end of its chain: " + supertypes);
    }

    /** The walk is a walk, not one hop. */
    @Test
    void aCompositionThroughTwoAliasHopsIsAdmitted() {
        TsonLinkedSchema linked = resolve("ofc2", """
                  rec => { x: int32 }
                  a1  => rec
                  a2  => a1
                  sub => a2 & { extra: text }
                """);

        assertTrue(linked.schema().entries().get("sub").supertypes().contains("rec"),
                () -> "the terminal is reached through both hops: "
                        + linked.schema().entries().get("sub").supertypes());
    }

    @Test
    void aRefinementThroughAnAliasToARecordIsAdmitted() {
        TsonLinkedSchema linked = resolve("ofc3", """
                  rec => { x: int32 }
                  ali => rec
                  sub => ali ^ { x: int32 = 1 }
                """);

        assertEquals(1, linked.schema().entries().get("sub").supertypes().stream()
                        .filter(name -> name.equals("rec")).count(),
                () -> "refinement reaches the same terminal: "
                        + linked.schema().entries().get("sub").supertypes());
    }

    /** §5.9 still empties the contract index -- what changes is only that the operand resolves at all. */
    @Test
    void aSubtractionThroughAnAliasToARecordIsAdmitted() {
        TsonLinkedSchema linked = resolve("ofc4", """
                  rec => { x: int32  y: int32 }
                  ali => rec
                  sub => ali - { y }
                """);

        assertEquals(List.of(), linked.schema().entries().get("sub").supertypes(),
                "subtraction revokes IS-A for every parent (§5.9)");
    }

    // ── Refused: the chain ends at something finished ────────────────────

    /** §4.3 names a template instantiation as finished, and an alias resolving to one with it. */
    @Test
    void aCompositionThroughAnAliasToAnInstantiationIsRefused() {
        assertTrue(refusal("ofc5", """
                  box => <V> { item: V }
                  bx  => box<text>
                  sub => bx & { extra: text }
                """).contains("has no fields to contribute"));
    }

    /** A top-level constructor application is a binding record: its bindings are set (§5.6). */
    @Test
    void aCompositionThroughAnAliasToAConstructionIsRefused() {
        assertTrue(refusal("ofc6", """
                  lookup => {text => int32}
                  ali    => lookup
                  sub    => ali & { extra: text }
                """).contains("has no fields to contribute"));
    }

    /** "A choice is likewise inadmissible -- it has variants, not fields" (§4.3). */
    @Test
    void aCompositionThroughAnAliasToAChoiceIsRefused() {
        assertTrue(refusal("ofc7", """
                  ch  => (text | int32)
                  ali => ch
                  sub => ali & { extra: text }
                """).contains("has no fields to contribute"));
    }

    @Test
    void aRefinementThroughAnAliasToAnInstantiationIsRefused() {
        assertTrue(refusal("ofc8", """
                  box => <V> { item: V }
                  bx  => box<text>
                  sub => bx ^ { item: text = "x" }
                """).contains("no vocabulary to tighten"));
    }
}
