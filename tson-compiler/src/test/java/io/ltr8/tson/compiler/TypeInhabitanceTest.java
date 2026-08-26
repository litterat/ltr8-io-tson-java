package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.TsonSchemaValidationException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §3.4.1 / §5.10.1's productivity rule: a type no finite document can satisfy is rejected where it is
 * written, not at the first document that tries to be one.
 *
 * <p>The rule is a least fixed point over the entry graph ({@link TypeInhabitance}), so what these fixtures
 * are really pinning is <b>where the base cases are</b> -- the four places a recursion is allowed to stop --
 * and that nothing else is mistaken for one.
 */
class TypeInhabitanceTest {

    private static final String ID = "https://example.test/inhabit.tn";

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/inhabit.tn"
                !!meta:"https://tson.io/2026/33/m/meta.tn"
                !!import:"https://tson.io/2026/33/m/core.tn"
                {
                %s
                }
                """.formatted(declarations);
        TsonSchemaSource source = uri -> {
            if (TsonCanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    /** Compiles without complaint -- the "no verdict" half of the deferred-checking rule. */
    private static void accepted(String declarations) {
        assertDoesNotThrow(() -> compile(declarations), () -> "expected no verdict for: " + declarations);
    }

    private static String rejection(String declarations) {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> compile(declarations), () -> "expected a rejection for: " + declarations);
        assertTrue(thrown.getMessage().contains("can never be satisfied"), thrown.getMessage());
        return thrown.getMessage();
    }

    // ── Rejected: the recursion never reaches a base case ────────────────

    /** #25's own example. Every {@code x} needs a {@code y}, which needs an {@code x}, forever. */
    @Test
    void aRequiredCycleBetweenTwoRecordsIsRejected() {
        assertTrue(rejection("""
                  x => { y: y }
                  y => { x: x }""").contains("x needs y needs x"), "the chain closes in the message");
    }

    @Test
    void aRecordRequiringItselfIsRejected() {
        assertTrue(rejection("  loop => { me: loop }").contains("loop needs loop"));
    }

    /** A floor above zero turns the base case off: an array can no longer be the empty one. */
    @Test
    void aRecursiveArrayWithAFloorIsRejected() {
        rejection("  node => { kids: [node; 1..] }");
    }

    @Test
    void aRecursiveMapWithAFloorIsRejected() {
        rejection("  m => { entries: {text => m; 1..} }");
    }

    /** A tuple position is required unless marked, so a tuple containing itself has no smallest value. */
    @Test
    void aTupleContainingItselfIsRejected() {
        rejection("  pair => [pair, text]");
    }

    /** Every member of a required group recurs, so there is no choice that terminates. */
    @Test
    void aRequiredGroupWhoseEveryMemberRecursIsRejected() {
        rejection("  g => { ( a: g | b: g ) }");
    }

    /**
     * A template is judged when it closes, not where it is written: its body is held until materialisation
     * substitutes, so the bounds and element types this rule reads are tokens meaning nothing until an
     * application supplies the arguments. The closure is an ordinary entry by the time linking runs, so
     * §8's own {@code tree} fixture is caught the moment anything applies it -- and a template nobody applies
     * is judged nowhere, the same answer §5.10's deferred checking gives everywhere else.
     */
    @Test
    void anUninhabitedTemplateIsRejectedWhereItCloses() {
        rejection("""
                  tree => <T> { value: T  children: [tree<T>; 1..] }
                  use  => tree<text>""");
    }

    /** The other half of the same rule: unapplied, it is not judged, and that is not a warning either. */
    @Test
    void aTemplateNobodyAppliesGetsNoVerdict() {
        accepted("  tree => <T> { value: T  children: [tree<T>; 1..] }");
    }

    /** An uninhabited variant is a mistake even where the choice around it still works. */
    @Test
    void anUninhabitedVariantIsRejectedThoughTheChoiceItselfIsFine() {
        rejection("""
                  leaf   => { v: text }
                  node   => { kids: [node; 1..] }
                  either => (leaf | node)""");
    }

    // ── Accepted: the four base cases ────────────────────────────────────

    /** An optional field: the recursion stops by leaving it out. */
    @Test
    void anOptionalFieldIsABaseCase() {
        assertNotNull(compile("  node => { v: text  next: node? }"));
    }

    /** A possibly-empty container: the recursion stops at {@code []}. */
    @Test
    void aPossiblyEmptyContainerIsABaseCase() {
        assertNotNull(compile("  node => { kids: [node] }"));
        assertNotNull(compile("  m    => { entries: {text => m} }"));
    }

    /** A choice needs one variant that terminates, not all of them. */
    @Test
    void aNonRecurringVariantIsABaseCase() {
        assertNotNull(compile("""
                  leaf   => { v: text }
                  branch => { kids: [either] }
                  either => (leaf | branch)"""));
    }

    /** An optional tuple position, and an optional array element, are base cases too. */
    @Test
    void anOptionalPositionIsABaseCase() {
        assertNotNull(compile("  pair => [pair?, text]"));
        assertNotNull(compile("  xs   => [xs?; 1..]"));
    }

    /**
     * A bound still held by a parameter counts as possibly-empty: {@code <N> [vec<N>; N]} could be applied
     * with {@code 0}, and refusing it would reject a template on the strength of an argument nobody has
     * supplied. The closure that does supply one is judged on its own.
     */
    @Test
    void aParameterBoundIsNotYetAFloor() {
        assertNotNull(compile("""
                  vec => <N> { kids: [vec<N>; N] }
                  use => { v: vec<0> }"""));
    }

    /**
     * An unresolved reference is <b>not</b> reported as uninhabited on top of being unresolved: one defect,
     * one diagnostic, in the words that name it.
     */
    @Test
    void anUnresolvedReferenceIsNotAlsoCalledUninhabited() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> compile("  holder => { v: nowhere }"));

        assertTrue(thrown.getMessage().contains("nowhere"), thrown.getMessage());
        assertTrue(!thrown.getMessage().contains("can never be satisfied"), thrown.getMessage());
    }

    /** Every bundled schema is inhabited -- the check runs over meta-kernel, meta.tn and core.tn on every link. */
    @Test
    void theBundledSchemasAllPass() {
        assertNotNull(compile("  ok => { v: text }"));
    }

    /** Collecting mode reports each uninhabited entry against itself rather than stopping at the first. */
    @Test
    void everyUninhabitedEntryIsReportedNotJustTheFirst() {
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("x", TypeDefinition.product(RecordBody.of(
                List.of(RecordField.required("y", TypeRef.of("y"))))));
        entries.put("y", TypeDefinition.product(RecordBody.of(
                List.of(RecordField.required("x", TypeRef.of("x"))))));

        TsonSchemaLinker.link(new TsonSchema("https://example.test/two.tn",
                TsonBundledSchemas.META_KERNEL_ID, List.of(), entries), null, problems);

        List<Diagnostic> reported = problems.diagnostics();
        assertTrue(reported.size() >= 2, () -> "expected one per entry, got " + reported);
        assertTrue(reported.stream().allMatch(d -> d.message().contains("can never be satisfied")),
                reported::toString);
    }
}
