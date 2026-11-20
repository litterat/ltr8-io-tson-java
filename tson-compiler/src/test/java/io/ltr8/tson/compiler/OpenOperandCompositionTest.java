package io.ltr8.tson.compiler;

import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.TypeChain;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Composing against an application of a parametric record that is <b>still open</b> -- one applied to the
 * absorbing declaration's own parameter, so it denotes no entry: {@code vip => <T> customer & box<T>}.
 *
 * <p><b>The fields need no materialisation, which is why absorption happens here.</b> The operand's body is
 * held, so its field set is known while the application is open; substituting its parameters with the
 * arguments as written yields a held record still carrying them, and absorbing that is an ordinary flattened
 * record that mentions {@code T}.
 *
 * <p><b>The IS-A edge is the half that has to wait.</b> No application is IS-A its head (§5.10), so the chain an
 * application of the open declaration closes to names {@code customer} and {@code box}'s own ancestors and
 * never {@code box} -- there is no instantiation of it yet to be IS-A. What the declaration <em>can</em> do is
 * keep the application: {@code supertypes} is a reference channel, so {@code box<T>} is written into the held
 * body as a parent and closes with everything else in it. {@code vip<text>} is therefore IS-A {@code box<text>} and not
 * {@code box<int32>}, which a name-level edge to {@code box} could not have distinguished.
 *
 * <p><b>The membership table is the real claim</b>, since a schema-driven read admits a value by the IS-A
 * index and nothing else. For {@code base => { tag }}, {@code box => <T> base & { value: T }} and
 * {@code vip => <T> customer & box<T>} closed at {@code vip<text>}:
 *
 * <table><caption>where a closed {@code vip<text>} stands</caption>
 * <tr><th>declared</th><th>admits it</th><th>why</th></tr>
 * <tr><td>{@code [customer]}</td><td>yes</td><td>composed directly</td></tr>
 * <tr><td>{@code [base]}</td><td>yes</td><td>carried through the open operand</td></tr>
 * <tr><td>{@code [box<text>]}</td><td>yes</td><td>the application closed with the body that held it</td></tr>
 * </table>
 */
class OpenOperandCompositionTest {

    private static final String ID = "https://example.test/open-operand.tn";

    /**
     * <b>A closed application at an operand takes the same route: it is absorbed by value.</b> The parametric record
     * is a macro at both ends: it contributes its fields and its own ancestors, and the type the composition
     * produces is the composing declaration (§5.8).
     *
     * <p><b>Neither {@code box<text>} nor {@code box} is a type to be IS-A here.</b> No application is IS-A its head
     * (§5.10), so no edge runs to it; no declaration names {@code box<text>}, so there is no entry for an edge to reach
     * either. What {@code c} IS-A is what {@code box}'s written parents reach: {@code base}.
     */
    @Test
    void aClosedApplicationAtAnOperandIsAbsorbedByValue() {
        TsonCompiledSchema compiled = compile("""
                  base => { tag: text }
                  box  => <V> base & { item: V }
                  c    => box<text> & { extra: text }
                """);

        assertTrue(compiled.schema().entries().keySet().stream().noneMatch(n -> n.startsWith("box_")),
                () -> "no declaration names box<text>, so there is no entry for it: "
                        + compiled.schema().entries().keySet());

        Top c = compiled.schema().entries().get("c");
        assertEquals(List.of("base"), TypeChain.supertypesOf(c),
                "the ancestors arrive by value, and no edge runs to the parametric record the operand applies");
        assertEquals(List.of("tag", "item", "extra"),
                ((RecordBody) c).fields().keySet().stream().toList(),
                "the operand's fields flatten in, left to right, with the body's own appended");
        assertEquals(List.of(), ((RecordBody) c).parents(),
                "a closed type keeps no parents: the application, which nothing names, reaches no edge");
    }

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/open-operand.tn"
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

    /** The lattice fixture the membership table is stated against, plus a name for the closed application. */
    private static final String LATTICE = """
              customer => { id: text }
              base     => { tag: text }
              box      => <T> base & { value: T }
              vip      => <T> customer & box<T>
              vip_text => vip<text>
              text_box => box<text>
            """;

    private static String aliasTarget(TsonCompiledSchema compiled, String alias) {
        // A `name => head<args>` declaration is the record its application closes to (§8.2), so the entry
        // every use site reaches is the declaration itself; an alias is followed one hop.
        return compiled.schema().entries().get(alias) instanceof io.ltr8.tson.schema.meta.Alias ref
                ? ref.target().name() : alias;
    }

    @Test
    void aCompositionAbsorbsAnOpenApplicationsFieldsAndKeepsTheParameter() {
        TsonCompiledSchema compiled = compile("""
                  customer => { id: text }
                  base     => { tag: text }
                  box      => <T> base & { value: T }
                  vip      => <T> customer & box<T>
                  use      => vip<text>
                """);

        RecordBody closed = (RecordBody) compiled.schema().entries()
                .get(aliasTarget(compiled, "use"));
        assertEquals(List.of("id", "tag", "value"), closed.fields().keySet().stream().toList(),
                "customer's field, then box's flattened field set, in composition order");
        assertEquals("text", closed.fields().values().stream().toList().get(2).typeExpr().text(),
                () -> "the parameter absorbed from box<T> closes with vip: "
                        + closed.fields().values().stream().toList().get(2));
    }

    /**
     * The open operand contributes its ancestors and not itself. {@code base} is a type and its fields
     * arrived, so the IS-A edge is real; {@code box} is a parametric record, and §5.10 makes no application IS-A its head.
     * The parametric record's chain says so: its parents as written, and the closed entries they reach.
     */
    @Test
    void anOpenOperandContributesItsOwnSupertypesButNotItsOwnName() {
        TsonCompiledSchema compiled = compile(LATTICE);

        Top vip = compiled.schema().entries().get("vip");

        assertEquals(List.of("base", "customer"), TypeChain.supertypesOf(vip),
                () -> "customer directly, base through the open operand: " + TypeChain.supertypesOf(vip));
        assertFalse(TypeChain.supertypesOf(vip).contains("box"),
                () -> "no application is IS-A its head: " + TypeChain.supertypesOf(vip));
        assertEquals(List.of("customer", "box<T>"), TypeChain.parentsOf(vip).stream()
                .map(io.ltr8.tson.schema.meta.TypeExpr::text).toList(), "and it keeps its parents as written");
    }

    /** Rows one and two of the membership table, read off the index a schema-driven read actually consults. */
    @Test
    void aClosedApplicationOfTheCompositionIsIndexedUnderBothItsRealSupertypes() {
        TsonCompiledSchema compiled = compile(LATTICE);
        String vipText = aliasTarget(compiled, "vip_text");

        assertTrue(TypeChain.subtypesOf(compiled.schema().entries().get("customer")).contains(vipText),
                () -> "customer: " + TypeChain.subtypesOf(compiled.schema().entries().get("customer")));
        assertTrue(TypeChain.subtypesOf(compiled.schema().entries().get("base")).contains(vipText),
                () -> "base: " + TypeChain.subtypesOf(compiled.schema().entries().get("base")));
    }

    /**
     * Row three, and the one that needs both sides closed. The hand-written {@code customer & box<text>}
     * gets this edge at its declaration; here it is derived when {@code vip<text>} closes, off the
     * application the held body kept, to the declaration naming that application -- the same entry, which is
     * what makes the two spellings one type rather than two that happen to have equal fields.
     */
    @Test
    void aClosedApplicationOfTheCompositionIsIndexedUnderTheClosedOperand() {
        TsonCompiledSchema compiled = compile(LATTICE);
        String vipText = aliasTarget(compiled, "vip_text");
        String boxText = aliasTarget(compiled, "text_box");

        assertTrue(TypeChain.subtypesOf(compiled.schema().entries().get(boxText)).contains(vipText),
                () -> boxText + ": " + TypeChain.subtypesOf(compiled.schema().entries().get(boxText)));
        assertTrue(TypeChain.supertypesOf(compiled.schema().entries().get(vipText)).contains(boxText),
                () -> vipText + ": " + TypeChain.supertypesOf(compiled.schema().entries().get(vipText)));
    }

    /**
     * The edge an argument list decides. {@code vip<text>} is IS-A {@code box<text>} and no other
     * instantiation of {@code box} -- the reason the parent is carried as a reference rather than as the
     * head name §5.8 describes, which would have been true of every instantiation at once.
     */
    @Test
    void theEdgeIsToTheInstantiationTheArgumentsName() {
        TsonCompiledSchema compiled = compile(LATTICE + "      int_box  => box<int32>\n");
        String vipText = aliasTarget(compiled, "vip_text");
        String intBox = aliasTarget(compiled, "int_box");

        assertFalse(TypeChain.subtypesOf(compiled.schema().entries().get(intBox)).contains(vipText),
                () -> intBox + ": " + TypeChain.subtypesOf(compiled.schema().entries().get(intBox)));
    }

    /** A body that only restates absorbed fields (§5.8): the same absorption, and the same two omissions. */
    @Test
    void aRestatementTightensAFieldSetAbsorbedFromAnOpenOperand() {
        TsonCompiledSchema compiled = compile("""
                  base => { tag: text  note: text }
                  box  => <T> base & { value: T }
                  vip  => <T> box<T> & { note?: text = "fixed" }
                  use  => vip<text>
                """);

        List<String> chain = TypeChain.supertypesOf(compiled.schema().entries().get(aliasTarget(compiled, "use")));
        assertTrue(chain.contains("base") && !chain.contains("box"),
                () -> "box is not a type and contributes no name to the chain; its own base does: " + chain);
        assertTrue(compiled.schema().applicationOf("vip").isEmpty(),
                () -> "an open source names no entry, so there is none to record: "
                        + compiled.schema().factsOf("vip"));

        RecordBody closed = (RecordBody) compiled.schema().entries()
                .get(aliasTarget(compiled, "use"));
        assertEquals(List.of("tag", "note", "value"), closed.fields().keySet().stream().toList());
        assertEquals("text", closed.fields().values().stream().toList().get(2).typeExpr().text(),
                "the parameter survived the restatement");
    }

    /**
     * The one position a bound reference cannot be written into: a reference's own head, which is a
     * {@code meta.type_name}. §5.10 admits no head abstraction, so this is refused where the author wrote it --
     * at the parametric record's declaration, before substitution has anything to say about it.
     */
    @Test
    void aParameterAppliedAsAHeadIsRefusedAtTheDeclarationThatWritesIt() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> compile("""
                          box => <T> { v: T<text> }
                          use => box<int32>
                        """));

        assertTrue(thrown.getMessage().contains("'box'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("no head abstraction"), thrown.getMessage());
    }

    /**
     * A constructor applied to a parameter at an operand gets the verdict its closed spelling gets, one phase
     * earlier: there is nothing for {@code &} to compose with.
     */
    @Test
    void composingWithAnOpenOperandThatHasNoFieldsIsRefused() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> compile("""
                          customer => { id: text }
                          vip      => <T> customer & meta.array<T>
                          use      => vip<text>
                        """));

        assertTrue(thrown.getMessage().contains("has no fields to contribute"), thrown.getMessage());
    }

    /** Arity is answered here too, from the operand's own declaration, rather than left to substitution. */
    @Test
    void applyingAnOpenOperandToTheWrongNumberOfArgumentsIsRefused() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> compile("""
                          customer => { id: text }
                          pair     => <A, B> { a: A  b: B }
                          vip      => <T> customer & pair<T>
                          use      => vip<text>
                        """));

        assertTrue(thrown.getMessage().contains("declares 2 type parameter(s) and is applied to 1"),
                thrown.getMessage());
    }

    /**
     * An application at an operand takes its head's written parents' edges (§5.8), an applied parent reaching the
     * entry a declaration gives it: {@code y} absorbs {@code wrap<text>}, whose head's parent {@code box<T>} is
     * {@code box<text>} there, which {@code tb} declares -- the edge {@code x => wrap<text>} has.
     */
    @Test
    void anOperandTakesTheEdgeItsHeadsAppliedParentReaches() {
        String heads = """
                  box  => <T> { v: T }
                  tb   => box<text>
                  wrap => <T> box<T> & { w: int32 }
                """;

        assertEquals(List.of("tb"), TypeChain.supertypesOf(
                compile(heads + "  y => wrap<text> & {}\n").schema().entries().get("y")));
        assertEquals(List.of("tb"), TypeChain.supertypesOf(
                compile(heads + "  x => wrap<text>\n").schema().entries().get("x")));
    }

    /** The walk goes on through each head's applied parents, to any depth, in both forms. */
    @Test
    void anAppliedParentsHeadsAppliedParentReachesItsDeclarationToo() {
        String heads = """
                  box    => <T> { v: T }
                  tb     => box<text>
                  wrap   => <T> box<T> & { w: int32 }
                  rewrap => <T> wrap<T> & { r: int32 }
                """;

        assertEquals(List.of("tb"), TypeChain.supertypesOf(
                compile(heads + "  y => rewrap<text> & {}\n").schema().entries().get("y")));
        assertEquals(List.of("tb"), TypeChain.supertypesOf(
                compile(heads + "  x => rewrap<text>\n").schema().entries().get("x")));
    }

    /** No edge runs to a head's own name, and an application no declaration names adds none (§5.10). */
    @Test
    void anAppliedParentNobodyDeclaresAddsNoEdge() {
        TsonCompiledSchema compiled = compile("""
                  box    => <T> { v: T }
                  tb     => box<int32>
                  wrap   => <T> box<T> & { w: int32 }
                  rewrap => <T> wrap<T> & { r: int32 }
                  y      => rewrap<text> & {}
                """);

        assertEquals(List.of(), TypeChain.supertypesOf(compiled.schema().entries().get("y")),
                "box<text> and wrap<text> are declared nowhere, tb is box<int32>, and no head is a type");
    }

    /**
     * Joining a family through a head's applied parent brings its obligation (§5.8): {@code tb} is an abstract base,
     * so {@code y} must pin its selector, as the declared {@code x => wrap<text>} must.
     */
    @Test
    void aMemberThroughAHeadsAppliedParentMustPinTheFamilysSelector() {
        String family = """
                  box  => abstract <T> { kind: text =?  v: T }
                  tb   => box<text>
                  wrap => <T> box<T> & { w: int32 }
                """;
        String composed = assertThrows(SchemaValidationException.class,
                () -> compile(family + "  y => wrap<text> & {}\n")).getMessage();
        String declared = assertThrows(SchemaValidationException.class,
                () -> compile(family + "  y => wrap<text>\n")).getMessage();

        assertEquals(declared, composed);
        assertTrue(composed.contains("'y'"), composed);
        assertEquals(List.of("tb"), TypeChain.supertypesOf(
                compile(family + "  y => wrap<text> & { kind?: = \"y\" }\n").schema().entries().get("y")));
    }
}
