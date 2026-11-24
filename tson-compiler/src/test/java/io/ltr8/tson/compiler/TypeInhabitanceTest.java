package io.ltr8.tson.compiler;

import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.base.SchemaValidationException;

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
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                %s
                }
                """.formatted(declarations);
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledVocabularyRegistry core =
                TsonCompiledVocabularyRegistry.withStandardLibrary(SchemaModelNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    /** Compiles without complaint -- the "no verdict" half of the deferred-checking rule. */
    private static void accepted(String declarations) {
        assertDoesNotThrow(() -> compile(declarations), () -> "expected no verdict for: " + declarations);
    }

    private static String rejection(String declarations) {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
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
     * An option is chosen whole ([TSON-SCHEMA] §5.11): it needs every member its group does not mark, so one
     * recurring unmarked member blocks it, while a recurring marked one can be left out.
     */
    @Test
    void anOptionIsChoosableWhenEveryUnmarkedMemberIs() {
        rejection("  g => { ( a: g  b: text | c: g ) }");
        accepted("  g => { ( a: text  b?: g | c: g ) }");
        accepted("  g => { ( a: g | b: text  c: text ) }");
    }

    /** At least one of the members is enough, so one that terminates is. */
    @Test
    void anAtLeastOneGroupNeedsOneMemberThatTerminates() {
        rejection("  g => { ( a: g | b: g )+ }");
        accepted("  g => { ( a: g | b: text )+ }");
    }

    /**
     * A parametric record is judged at its declaration, its parameters assumed inhabited (§5.10.1): §8's {@code tree}
     * fixture, its recursive position non-empty through an incomplete entry's completion, has no finite value whatever
     * {@code T} is, so it is refused whether or not anything applies it, and the verdict names it rather than an applier.
     */
    @Test
    void anUninhabitedParametricRecordIsRejectedAtItsDeclarationAppliedOrNot() {
        String tree = """
                  nonempty => !meta.array ^ { min_items: 1 }
                  tree => <T> { value: T  children: nonempty<tree<T>> }""";

        assertTrue(rejection(tree).startsWith("'tree' can never be satisfied"), rejection(tree));
        String applied = rejection(tree + "\n  use  => tree<text>");
        assertTrue(applied.startsWith("'tree' can never be satisfied"), applied);
    }

    /**
     * The chain runs through the application the parametric record holds: the construction {@code
     * nonempty<tree<T>>} closes to is written in place at {@code children}, no link of its own, and the application
     * inside it is inhabited where its head is.
     */
    @Test
    void theChainRunsThroughTheApplicationTheParametricRecordHolds() {
        String message = rejection("""
                  nonempty => !meta.array ^ { min_items: 1 }
                  tree => <T> { value: T  children: nonempty<tree<T>> }""");

        assertTrue(message.contains("tree needs tree"), message);
    }

    @Test
    void anUnguardedSelfRecursionInAParametricRecordNobodyAppliesIsRejected() {
        assertTrue(rejection("  loop => <T> { v: T  next: loop<T> }").contains("loop needs loop"));
    }

    /** A guarded recursion is productive at the declaration whether or not anything applies it. */
    @Test
    void aGuardedParametricRecordIsAcceptedAppliedOrNot() {
        String records = """
                  linked_list => <T> { value: T  next?: linked_list<T> }
                  tree        => <T> { value: T  children?: [tree<T>] }
                  a           => <T> { v: T  b?: b<T> }
                  b           => <T> { w: T  a: a<T> }""";

        accepted(records);
        accepted(records + """

                  ll => linked_list<text>
                  tr => tree<int32>
                  ab => a<text>""");
    }

    /** A parameter is assumed inhabited, so a required field typed by one demands nothing the declaration can refuse. */
    @Test
    void aRequiredParameterTypedFieldIsAssumedInhabited() {
        accepted("  box => <T> { v: T  w: [T; 1..]  p: (T | text) }");
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
     * An unresolved reference is <b>not</b> reported as uninhabited on top of being unresolved: one defect,
     * one diagnostic, in the words that name it.
     */
    @Test
    void anUnresolvedReferenceIsNotAlsoCalledUninhabited() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> compile("  holder => { v: nowhere }"));

        assertTrue(thrown.getMessage().contains("nowhere"), thrown.getMessage());
        assertTrue(!thrown.getMessage().contains("can never be satisfied"), thrown.getMessage());
    }

    /** Every bundled schema is inhabited -- the check runs over every bundled schema on every link. */
    @Test
    void theBundledSchemasAllPass() {
        assertNotNull(compile("  ok => { v: text }"));
    }

    /** Collecting mode reports each uninhabited entry against itself rather than stopping at the first. */
    @Test
    void everyUninhabitedEntryIsReportedNotJustTheFirst() {
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("x", RecordBody.of(
                RecordBody.fieldMap(Map.entry("y", RecordField.required(TypeRef.of("y"))))));
        entries.put("y", RecordBody.of(
                RecordBody.fieldMap(Map.entry("x", RecordField.required(TypeRef.of("x"))))));

        TsonSchemaLinker.link(new TsonSchema("https://example.test/two.tn",
                List.of(), entries), null, problems);

        List<Diagnostic> reported = problems.diagnostics();
        assertTrue(reported.size() >= 2, () -> "expected one per entry, got " + reported);
        assertTrue(reported.stream().allMatch(d -> d.message().contains("can never be satisfied")),
                reported::toString);
    }
}
