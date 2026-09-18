package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
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
 * admitted; an alias whose chain ends at a body with no fields -- a top-level constructor application
 * (§5.6) or a choice -- is finished and admits neither operator.
 *
 * <p><b>The body is the whole test</b> ({@code SPEC-FEEDBACK.md} #16). A record template's instantiation
 * closes to a {@code !record} carrying fields, so it merges and tightens like the hand-written record of the
 * same shape; §8.2 makes identity the question rather than provenance, so which of the two an author wrote
 * cannot decide it. Every other instantiation is refused by that same body test, having no fields of its own.
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

    // ── Admitted: the chain ends at an instantiation that is a record ────

    /**
     * <b>A record template's instantiation composes like the record it is</b> ({@code SPEC-FEEDBACK.md}
     * #16). {@code bx} closes to {@code !record { item: text }}, which is what §4.3's MUST asks for, so
     * there is a field set to merge. Refusing it would make composition depend on whether an application or
     * a pen produced those fields, which §8.2 rules out: what is canonicalised is identity, not provenance.
     */
    @Test
    void aCompositionThroughAnAliasToARecordInstantiationIsAdmitted() {
        TsonLinkedSchema linked = resolve("ofc5", """
                  box => <V> { item: V }
                  bx  => box<text>
                  sub => bx & { extra: text }
                """);

        assertEquals(List.of("item", "extra"), fieldNames(linked, "sub"),
                "the operand's own field, then the composition's");
        assertTrue(linked.schema().entries().get("sub").supertypes().contains("bx"),
                () -> "and IS-A the operand: " + linked.schema().entries().get("sub").supertypes());
    }

    /** The refinement half: a vocabulary to tighten is a vocabulary however the entry came by it. */
    @Test
    void aRefinementThroughAnAliasToARecordInstantiationIsAdmitted() {
        TsonLinkedSchema linked = resolve("ofc8", """
                  box => <V> { item: V }
                  bx  => box<text>
                  sub => bx ^ { item: text = "x" }
                """);

        assertEquals(List.of("item"), fieldNames(linked, "sub"), "refinement adds no field (§5.7)");
        assertTrue(linked.schema().entries().get("sub").supertypes().contains("bx"),
                () -> "and preserves IS-A: " + linked.schema().entries().get("sub").supertypes());
    }

    // ── Refused: the chain ends at a body with no fields ─────────────────

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

    /**
     * The same body test refuses an instantiation that is <em>not</em> a record: {@code vector<text, 3>}
     * closes to an {@code !array}, which has elements rather than fields. This is what keeps §4.3's
     * "finished" idea intact where it was always doing the work.
     */
    @Test
    void aCompositionThroughAnAliasToANonRecordInstantiationIsRefused() {
        assertTrue(refusal("ofc9", """
                  vector => <T, N> !array { element_type: T  min_items: N  max_items: N }
                  triple => vector<text, 3>
                  sub    => triple & { extra: text }
                """).contains("has no fields to contribute"));
    }

    /** The field names of an entry's record body, in declaration order. */
    private static List<String> fieldNames(TsonLinkedSchema linked, String entry) {
        return ((RecordBody) linked.schema().entries().get(entry).body()).fields().stream()
                .map(RecordField::name).toList();
    }
}
