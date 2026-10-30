package io.ltr8.tson;
import io.ltr8.tson.base.ProcessorConfig;

import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.Alias;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>What {@code !C { ... }} may apply is decided by IS-A {@code top} ([TSON-SCHEMA] §4.1), not by the
 * {@code ~} marker.</b> §4.1 makes every base kind IS-A {@code top} and every constructor transitively so,
 * while IS-A stops at construction — an instance or a fresh record carries an empty chain. So the predicate
 * admits every constructor and, beyond them, exactly the entries describing <em>a type</em> rather than a
 * part of one.
 *
 * <p><b>Asking whether {@code C} is a constructor was both too narrow and inconsistent.</b> Too narrow:
 * {@code alias} is deliberately unmarked in the kernel (it describes no value) and the language needs it
 * applicable, so it took a by-name exception in the template path and none in the closed one — leaving
 * {@code <T> !alias { target: T }} legal and {@code !alias { target: int32 }} refused, one
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
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                %s }
                """.formatted(declarations);
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(ID, source)))))
                .validateSchema(source);
    }

    /**
     * Both spellings apply {@code alias}: a closed application and an open one resolve by the same
     * applicability rule, {@code alias} being IS-A {@code top}.
     */
    @Test
    void aliasAppliesClosedAndOpenAlike() {
        assertEquals(List.of(), problems("  r => !alias { target: int32 }"));
        assertEquals(List.of(), problems("""
                  r => <T> !alias { target: T }
                  c => r<int32>
                """));
    }

    /**
     * <b>The closed spelling denotes the same entry the bare alias does.</b> Admitting it is only half the
     * job: {@code !alias { target: X }} is the explicit form of {@code name => X} (§8.3), so it must
     * resolve to {@code kind: ALIAS} with {@code X} as source and body — not to the head's own kind with
     * {@code alias} as source, which is what a construction of any other head yields.
     *
     * <p>The distinction was unreachable while the closed form was refused, which is why it is asserted here
     * against all three spellings at once rather than left to the one that already worked.
     */
    @Test
    void everySpellingOfAnAliasDenotesTheSameEntry() {
        String source = """
                !!id:"https://example.test/u.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  bare    => int32
                  closed  => !alias { target: int32 }
                  open    => <T> !alias { target: T }
                  applied => open<int32>
                }
                """;
        var entries = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(ID, source)))))
                .resolve(source).schema().entries();

        for (String name : List.of("bare", "closed", "applied")) {
            Top definition = entries.get(name);
            assertEquals(EntryKind.ALIAS, EntryKind.of(definition, n -> true), name);
            assertEquals("int32", ((Alias) definition).target().name(), name);
        }
        // The open one is a template until it closes, and its kind says so: a template is not a type
        // (§5.10), so it is TEMPLATE rather than the ALIAS its closure produces. `applied` above is
        // where the ALIAS appears -- the entry closing it mints.
        assertEquals(EntryKind.TEMPLATE, EntryKind.of(entries.get("open"), n -> true));
    }

    /**
     * <b>An alias is a hop, not a rewrite (§8.3), so applicability follows the chain.</b> An alias to a
     * constructor is applicable, because the entry at the end of the chain is — and so is an alias to that
     * alias. Everything the head is asked (is it a template, is it applicable, what kind does construction
     * transfer, whose vocabulary reads the payload) is a question about the terminal entry.
     *
     * <p>Asking the alias instead would answer every one of them from an empty supertype chain and a {@code
     * ALIAS} kind, which is what a hop looks like rather than what it points at.
     */
    @Test
    void applicationFollowsAnAliasToItsTarget() {
        String meta = """
                !!id:"https://example.test/m.tn"
                !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
                !schema {
                  alias_array => array
                  two_hops    => alias_array
                }
                """;
        for (String head : List.of("array", "alias_array", "two_hops")) {
            String user = """
                    !!id:"https://example.test/u.tn"
                    !!import:"https://example.test/m.tn"
                    !!import:"https://tson.io/2026/39/m/core.tn"
                    !schema { tags => !%s { element_type: text } }
                    """.formatted(head);
            List<Diagnostic> problems = Tson.of(ProcessorConfig.defaults()
                    .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(
                            "https://example.test/m.tn", meta, "https://example.test/u.tn", user))))).validateSchema(user);

            assertEquals(List.of(), problems, () -> "!" + head + " { ... }: " + problems);
        }
    }

    /** Every ordinary constructor is IS-A top, so each applies. */
    @Test
    void anOrdinaryConstructorStillApplies() {
        assertEquals(List.of(), problems("  tags => !array { element_type: text }"));
    }

    /**
     * A component of a type -- a record type IS-A no constructor -- is an ordinary type, and {@code !C { ... }} with
     * {@code C} one of them is a value of it ([TSON-SCHEMA] §4.1), read against it: a body that is no value of it is
     * refused as such, and one that is resolves to a value entry.
     */
    @Test
    void aComponentOfATypeBuildsAValueOfIt() {
        for (String component : List.of("record_field { name: x  type: text }", "tuple_element { label: text }",
                "field_group { members: g }", "integer_size { bits: 32  sign: true }")) {
            List<Diagnostic> problems = problems("  bad => !" + component);

            assertEquals(1, problems.size(), problems::toString);
            assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code());
            assertTrue(problems.getFirst().message().contains("the body is no value of the type"), problems::toString);
        }
        assertEquals(List.of(), problems("  good => !integer_size { bits: 32  signed: true }"));
    }

    /**
     * A base kind is admitted by the predicate and refused as abstract ([TSON-SCHEMA] §4.1), which is the
     * better answer: it names the instantiable subtypes that would satisfy the position instead of saying the head
     * was the wrong kind of thing.
     */
    @Test
    void aBaseKindIsAdmittedAndThenRefusesItself() {
        List<Diagnostic> problems = problems("  bad => !product {}");

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("'!product' names an abstract entry"), problems::toString);
        assertTrue(problems.getFirst().message().contains("(namespace | record | array | set_type | map | tuple)"),
                problems::toString);
        assertFalse(problems.getFirst().message().contains("constraint vocabulary"),
                "one sentence, not the applicability clause beside the abstract one: " + problems);
    }

    /** {@code top}'s refusal names only what can be constructed: {@code type} and the base kinds are abstract too. */
    @Test
    void topNamesOnlyItsInstantiableSubtypes() {
        String message = problems("  bad => !top {}").getFirst().message();

        assertTrue(message.contains("record"), message);
        for (String abstractKind : List.of("type", "atom", "product", "sum", "template")) {
            assertFalse(message.matches("(?s).*[(| ]" + abstractKind + "[ |)].*"),
                    () -> abstractKind + " is no answer: " + message);
        }
    }

    /**
     * With nothing in scope composing it, an abstract constructor's refusal says so rather than naming an empty
     * list: a meta layer's own {@code marker => abstract top & {}}, which nothing composes, applied in a schema it
     * governs.
     */
    @Test
    void anAbstractConstructorWithNothingComposingItSaysSo() {
        String meta = """
                !!id:"https://example.test/marker-meta.tn"
                !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema {
                  marker => abstract top & {}
                }
                """;
        String user = """
                !!id:"https://example.test/marked.tn"
                !!import:"https://example.test/marker-meta.tn"
                !schema { bad => !marker {} }
                """;
        List<Diagnostic> problems = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(
                        "https://example.test/marker-meta.tn", meta, "https://example.test/marked.tn", user)))))
                .validateSchema(user);
        assertEquals(1, problems.size(), problems::toString);
        String message = problems.getFirst().message();

        assertTrue(message.contains("no constructor in scope composes 'marker'"), message);
        assertFalse(message.contains("()"), message);
    }
}
