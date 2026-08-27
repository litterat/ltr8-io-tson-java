package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Composing and refining against a template application that is <b>still open</b> -- one applied to the
 * absorbing declaration's own parameter, so it denotes no entry: {@code vip => <T> customer & box<T>}.
 *
 * <p><b>Nothing here needs materialisation, which is the point.</b> The operand's body is held, so its field
 * set is known while the application is open; substituting its parameters with the arguments as written
 * yields a held record still carrying them, and absorbing that is an ordinary flattened record that mentions
 * {@code T}. What the operand does <em>not</em> contribute is its own name: a template is not a type (§5.10),
 * so nothing can be IS-A one. Its ancestors are types and do come through, which is what keeps a declaration
 * composing {@code box<T>} usable where {@code box}'s own {@code base} is expected.
 *
 * <p><b>The membership table is the real claim</b>, since a schema-driven read admits a value by the IS-A
 * index and nothing else. For {@code base => { tag }}, {@code box => <T> base & { value: T }} and
 * {@code vip => <T> customer & box<T>} closed at {@code vip<text>}:
 *
 * <table><caption>where a closed {@code vip<text>} stands</caption>
 * <tr><th>declared</th><th>admits it</th><th>why</th></tr>
 * <tr><td>{@code [customer]}</td><td>yes</td><td>composed directly</td></tr>
 * <tr><td>{@code [base]}</td><td>yes</td><td>carried through the open operand</td></tr>
 * <tr><td>{@code [box<text>]}</td><td><b>no</b></td><td>deliberate -- see below</td></tr>
 * </table>
 *
 * <p>The third row is what flattening at the declaration costs. The application is absorbed away here, so
 * when {@code vip<text>} is minted nothing remains that says "close {@code box<text>} too, and index against
 * the entry that mints" -- where the hand-written {@code customer & box<text>} names that entry and gets the
 * edge. Accepted rather than worked around: {@code box<T>} was never a type in {@code vip}'s declaration, so
 * {@code vip} claimed IS-A with no instantiation of it in particular. Pinned below so the day it changes is a
 * decision rather than a surprise.
 */
class OpenOperandCompositionTest {

    private static final String ID = "https://example.test/open-operand.tn";

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/open-operand.tn"
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
        // A `name => head<args>` declaration is a REFERENCE entry whose source is the application; the entry
        // materialisation minted for it is what every use site flattens to, and what the index keys on.
        return compiled.schema().entries().get(alias).source().orElseThrow().name();
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
                .get(aliasTarget(compiled, "use")).body();
        assertEquals(List.of("id", "tag", "value"), closed.fields().stream().map(f -> f.name()).toList(),
                "customer's field, then box's flattened field set, in composition order");
        assertEquals("text", closed.fields().get(2).type().name(),
                () -> "the parameter absorbed from box<T> closes with vip: " + closed.fields().get(2));
    }

    /**
     * The open operand contributes its ancestors and not itself. {@code base} is a type and its fields
     * arrived, so the IS-A edge is real; {@code box} is a template, and §5.10 makes a template no type.
     */
    @Test
    void anOpenOperandContributesItsOwnSupertypesButNotItsOwnName() {
        TsonCompiledSchema compiled = compile(LATTICE);

        TypeDefinition vip = compiled.schema().entries().get("vip");

        assertEquals(List.of("customer", "base"), vip.supertypes(),
                () -> "customer directly, base through the open operand: " + vip.supertypes());
        assertFalse(vip.supertypes().contains("box"),
                () -> "a template is not a type, so nothing is IS-A one: " + vip.supertypes());
    }

    /** Rows one and two of the membership table, read off the index a schema-driven read actually consults. */
    @Test
    void aClosedApplicationOfTheCompositionIsIndexedUnderBothItsRealSupertypes() {
        TsonCompiledSchema compiled = compile(LATTICE);
        String vipText = aliasTarget(compiled, "vip_text");

        assertTrue(compiled.schema().entries().get("customer").subtypes().contains(vipText),
                () -> "customer: " + compiled.schema().entries().get("customer").subtypes());
        assertTrue(compiled.schema().entries().get("base").subtypes().contains(vipText),
                () -> "base: " + compiled.schema().entries().get("base").subtypes());
    }

    /**
     * Row three, and the one deliberate no. Flattening at the declaration absorbs the application away, so
     * the closed composition is not indexed under the closed operand -- where the hand-written
     * {@code customer & box<text>} would be. Changing this means keeping the application until the absorbing
     * declaration closes, which is a different design and not a fix to this one.
     */
    @Test
    void aClosedApplicationOfTheCompositionIsNotIndexedUnderTheClosedOperand() {
        TsonCompiledSchema compiled = compile(LATTICE);
        String vipText = aliasTarget(compiled, "vip_text");
        String boxText = aliasTarget(compiled, "text_box");

        assertFalse(compiled.schema().entries().get(boxText).subtypes().contains(vipText),
                () -> boxText + ": " + compiled.schema().entries().get(boxText).subtypes());
    }

    /** §5.7 against an open source: the same absorption, and the same two omissions. */
    @Test
    void aRefinementTightensAFieldSetAbsorbedFromAnOpenSource() {
        TsonCompiledSchema compiled = compile("""
                  base => { tag: text  note: text }
                  box  => <T> base & { value: T }
                  vip  => <T> box<T> ^ { note: text = "fixed" }
                  use  => vip<text>
                """);

        TypeDefinition vip = compiled.schema().entries().get("vip");
        assertEquals(List.of("base"), vip.supertypes(),
                () -> "box is not a type and contributes no name; its own base does: " + vip.supertypes());
        assertTrue(vip.source().isEmpty(),
                () -> "an open source names no entry, so there is none to record: " + vip.source());

        RecordBody closed = (RecordBody) compiled.schema().entries()
                .get(aliasTarget(compiled, "use")).body();
        assertEquals(List.of("tag", "note", "value"), closed.fields().stream().map(f -> f.name()).toList());
        assertEquals("text", closed.fields().get(2).type().name(), "the parameter survived the refinement");
    }

    /**
     * One template applied to another, at an absorbing position and still open. Substitution writes a bound
     * reference through {@code SchemaDesugarer.refValue}, which spells one carrying arguments in {@code
     * type_ref}'s record form rather than as a bare token, so {@code inner<T>} survives whole and the
     * absorbing declaration's own materialisation closes it. Writing the head name alone dropped the argument
     * list with no diagnostic, which is why this was refused as a gap rather than closed wrongly.
     */
    @Test
    void anArgumentThatIsItselfAnApplicationSurvivesSubstitutionWhole() {
        TsonCompiledSchema compiled = compile("""
                  plain => { n: int32 }
                  inner => <U> { u: U }
                  box   => <T> { v: T }
                  vip   => <T> plain & box<inner<T>>
                  use   => vip<text>
                """);

        RecordBody closed = (RecordBody) compiled.schema().entries()
                .get(aliasTarget(compiled, "use")).body();
        String v = closed.fields().stream().filter(f -> f.name().equals("v")).findFirst().orElseThrow()
                .type().name();

        RecordBody held = (RecordBody) compiled.schema().entries().get(v).body();
        assertEquals(List.of("u"), held.fields().stream().map(f -> f.name()).toList(),
                () -> "'v' should name the inner<text> instantiation, not bare 'inner': " + v);
        assertEquals("text", held.fields().get(0).type().name(),
                "the argument list survived, so U closed to text");
    }

    /**
     * The one position a bound reference cannot be written into: a {@code type_ref}'s own head, which is a
     * {@code type_name}. §5.10 admits no head abstraction, so this is refused where the author wrote it --
     * at the template's declaration, before substitution has anything to say about it.
     */
    @Test
    void aParameterAppliedAsAHeadIsRefusedAtTheDeclarationThatWritesIt() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> compile("""
                          box => <T> { v: T<text> }
                          use => box<int32>
                        """));

        assertTrue(thrown.getMessage().contains("'box'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("no head abstraction"), thrown.getMessage());
    }

    /**
     * An open operand with elements rather than fields gets the verdict its closed spelling gets, one phase
     * earlier: there is nothing for {@code &} to compose with.
     */
    @Test
    void composingWithAnOpenOperandThatHasNoFieldsIsRefused() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> compile("""
                          customer => { id: text }
                          list     => <T> [T]
                          vip      => <T> customer & list<T>
                          use      => vip<text>
                        """));

        assertTrue(thrown.getMessage().contains("has no fields to contribute"), thrown.getMessage());
    }

    /** Arity is answered here too, from the operand's own declaration, rather than left to substitution. */
    @Test
    void applyingAnOpenOperandToTheWrongNumberOfArgumentsIsRefused() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> compile("""
                          customer => { id: text }
                          pair     => <A, B> { a: A  b: B }
                          vip      => <T> customer & pair<T>
                          use      => vip<text>
                        """));

        assertTrue(thrown.getMessage().contains("declares 2 type parameter(s) and is applied to 1"),
                thrown.getMessage());
    }
}
