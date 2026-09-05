package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>What {@code !C { ... }} may apply is decided by IS-A {@code top} ([TSON-SCHEMA] §4.1), not by the
 * {@code ~} marker.</b> §4.1 makes every base kind IS-A {@code top} and every constructor transitively so,
 * while IS-A stops at construction — an instance or a fresh record carries an empty chain. So the predicate
 * admits every constructor and, beyond them, exactly the entries describing <em>a type</em> rather than a
 * part of one.
 *
 * <p><b>Asking whether {@code C} is a constructor was both too narrow and inconsistent.</b> Too narrow:
 * {@code reference} is deliberately unmarked in the kernel (it describes no value) and the language needs it
 * applicable, so it took a by-name exception in the template path and none in the closed one — leaving
 * {@code <T> !reference { target: T }} legal and {@code !reference { target: int32 }} refused, one
 * construction with two answers. Inconsistent, because nothing about the marker said which.
 *
 * <p>The marker keeps its other jobs — §4.2's level discipline reads it, and §8.1 records it. What it no
 * longer decides is applicability.
 */
class ApplicabilityIsIsATopTest {

    private static final String ID = "https://example.test/u.tn";

    private static List<Diagnostic> problems(String declarations) {
        String source = """
                !!id:"https://example.test/u.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                {
                %s }
                """.formatted(declarations);
        return Tson.builder().schemaSource(TsonSchemaSource.ofMap(Map.of(ID, source))).build()
                .validateSchema(source);
    }

    /**
     * The asymmetry this replaced. Both spellings apply {@code reference}; before, only the open one
     * resolved, because {@code resolveTemplateInstance} carried {@code REFERENCE.equals(target)} and
     * {@code resolveInstance} did not.
     */
    @Test
    void referenceAppliesClosedAndOpenAlike() {
        assertEquals(List.of(), problems("  r => !reference { target: int32 }"));
        assertEquals(List.of(), problems("""
                  r => <T> !reference { target: T }
                  c => r<int32>
                """));
    }

    /**
     * <b>The closed spelling denotes the same entry the bare alias does.</b> Admitting it is only half the
     * job: {@code !reference { target: X }} is the explicit form of {@code name => X} (§8.3), so it must
     * resolve to {@code kind: REFERENCE} with {@code X} as source and body — not to the head's own kind with
     * {@code reference} as source, which is what a construction of any other head yields.
     *
     * <p>The distinction was unreachable while the closed form was refused, which is why it is asserted here
     * against all three spellings at once rather than left to the one that already worked.
     */
    @Test
    void everySpellingOfAnAliasDenotesTheSameEntry() {
        String source = """
                !!id:"https://example.test/u.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                {
                  bare    => int32
                  closed  => !reference { target: int32 }
                  open    => <T> !reference { target: T }
                  applied => open<int32>
                }
                """;
        var entries = Tson.builder().schemaSource(TsonSchemaSource.ofMap(Map.of(ID, source))).build()
                .resolve(source).schema().entries();

        for (String name : List.of("bare", "closed", "applied")) {
            TypeDefinition definition = entries.get(name);
            assertEquals(TypeKind.REFERENCE, definition.kind(), name);
            assertEquals("int32", definition.source().orElseThrow().name(), name);
        }
        // The open one is a template until it closes, and its kind says so: a template is not a type
        // (§5.10), so it is TEMPLATE rather than the REFERENCE its closure produces. `applied` above is
        // where the REFERENCE appears -- the entry closing it mints.
        assertEquals(TypeKind.TEMPLATE, entries.get("open").kind());
    }

    /**
     * <b>A reference is a hop, not a rewrite (§8.3), so applicability follows the chain.</b> An alias to a
     * constructor is applicable, because the entry at the end of the chain is — and so is an alias to that
     * alias. Everything the head is asked (is it a template, is it applicable, what kind does construction
     * transfer, whose vocabulary reads the payload) is a question about the terminal entry.
     *
     * <p>Asking the alias instead answered every one of them from an empty supertype chain and a {@code
     * REFERENCE} kind, which is what a hop looks like rather than what it points at. The old {@code
     * constructor: true} flag had the same hole — it was hardcoded {@code false} on a reference — so this is
     * a defect the predicate inherited rather than one it introduced.
     */
    @Test
    void applicationFollowsAnAliasToItsTarget() {
        String meta = """
                !!id:"https://example.test/m.tn"
                !!meta:"https://tson.io/2026/35/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/35/m/meta-kernel.tn"
                {
                  alias_array => array
                  two_hops    => alias_array
                }
                """;
        for (String head : List.of("array", "alias_array", "two_hops")) {
            String user = """
                    !!id:"https://example.test/u.tn"
                    !!meta:"https://example.test/m.tn"
                    !!import:"https://tson.io/2026/35/m/meta-kernel.tn"
                    { tags => !%s { element_type: identifier } }
                    """.formatted(head);
            List<Diagnostic> problems = Tson.builder()
                    .schemaSource(TsonSchemaSource.ofMap(Map.of(
                            "https://example.test/m.tn", meta, "https://example.test/u.tn", user)))
                    .build().validateSchema(user);

            assertEquals(List.of(), problems, () -> "!" + head + " { ... }: " + problems);
        }
    }

    /** Every ordinary constructor is IS-A top, so nothing that worked before stops working. */
    @Test
    void anOrdinaryConstructorStillApplies() {
        assertEquals(List.of(), problems("  tags => !array { element_type: text }"));
    }

    /**
     * A component of a type — record-bodied, empty chain — is refused where it is written. Without the check
     * it fails anyway, on {@code Top} being sealed, but as a {@code ClassCastException} surfaced as {@code
     * NOT_IMPLEMENTED}: a non-verdict code, and a message about JVM module loaders, for an author's mistake.
     */
    @Test
    void aComponentOfATypeIsRefusedWhereItIsWritten() {
        for (String component : List.of("record_field { name: x  type: text }", "type_ref { name: text }",
                "field_group { name: g  members: [] }", "integer_size { bits: 32  signed: true }")) {
            List<Diagnostic> problems = problems("  bad => !" + component);

            assertEquals(1, problems.size(), problems::toString);
            assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code());
            assertTrue(problems.getFirst().message().contains("not IS-A 'top'"), problems::toString);
        }
    }

    /**
     * A base kind is admitted by the predicate and refused by its own reader, which is the better answer:
     * it names the subtypes that would satisfy the position instead of saying the head was the wrong kind
     * of thing.
     */
    @Test
    void aBaseKindIsAdmittedAndThenRefusesItself() {
        List<Diagnostic> problems = problems("  bad => !product {}");

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("has no data of its own to bind"), problems::toString);
    }
}
