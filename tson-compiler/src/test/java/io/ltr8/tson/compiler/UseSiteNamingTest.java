package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A diagnostic names the type <b>the author wrote at that position</b>, not the entry at the end of the
 * reference chain it resolves to.
 *
 * <p>[TSON-SCHEMA] §8.3 flattens a type position past a {@code REFERENCE} entry and records the name the
 * author wrote as {@code @alias} on the type-ref, so {@code c: pct} over {@code pct => small} arrives at the
 * reader as {@code small} -- one shared reader, correctly named {@code small} for a position that names it
 * directly, and wrong for one that does not. The walk stops at a materialised instantiation instead, so
 * {@code e: b<10>} names an entry whose own content-derived key ({@code integer_type_10_100_786fbcfb})
 * appears nowhere in the author's file at all.
 *
 * <p>Both are fixed the same way and in the same place: the name is chosen where a composite reader wires
 * its children, which is <b>compile time</b>. Nothing is added to {@code TsonReadContext} and no per-read
 * allocation changes -- {@code AllocationHarnessTest} is the guard on that, and a position with no alias
 * gets the shared reader back unchanged.
 *
 * <p>This is the naming twin of the rule {@code SchemaLocation} already follows for pointers: the pointer is
 * the path taken, never the leaf it resolves to.
 */
class UseSiteNamingTest {

    private static final String ID = "https://example.test/naming.tn";

    private static final String PRELUDE = """
              small => !integer ^ { max: 100 }
              pct   => small
              outer => pct
              b     => <N> !integer_type { min: N  max: 100 }
            """;

    /** Every diagnostic a read of {@code data} against {@code declarations} reports, message only. */
    private static List<String> messages(String declarations, String data) {
        String schema = """
                !!id:"https://example.test/naming.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                {
                %s%s
                }
                """.formatted(PRELUDE, declarations);
        TsonSchemaSource source = uri -> {
            if (TsonCanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        TsonCompiledSchema compiled = TsonCompiledSchemaRegistry.tree(core).get(ID);
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        compiled.get("r").read(TestDocuments.document(data, problems));
        return problems.diagnostics().stream().map(Diagnostic::message).toList();
    }

    /**
     * <b>The alias case, one hop and two.</b> All three fields resolve to the same entry and the same shared
     * reader; each is named as its own declaration writes it. {@code a} is the control -- a position that
     * names {@code small} directly still says {@code small}, which is the behaviour the rename must not
     * disturb.
     */
    @Test
    void eachPositionIsNamedAsItsOwnDeclarationWritesIt() {
        List<String> found = messages("  r => { a: small  c: pct  d: outer }", "!r { a: 500  c: 500  d: 500 }");

        assertEquals(3, found.size(), found::toString);
        assertTrue(found.get(0).startsWith("'small':"), found.get(0));
        assertTrue(found.get(1).startsWith("'pct':"), found.get(1));
        assertTrue(found.get(2).startsWith("'outer':"), found.get(2));
    }

    /**
     * <b>A materialised application names the application.</b> No {@code @alias} exists here -- §8.3's walk
     * stops at an instantiation -- so the name comes from the other carrier: the instantiation entry's own
     * {@code source} is {@code b<10>}, and the entry that refers is what names the reader it refers to.
     */
    @Test
    void aMaterialisedApplicationNamesTheApplicationTheAuthorWrote() {
        List<String> found = messages("  r => { e: b<10> }", "!r { e: 5 }");

        assertEquals(1, found.size(), found::toString);
        assertTrue(found.getFirst().startsWith("'b<10>':"), found.getFirst());
        assertFalse(found.getFirst().matches("(?s).*\\w+_[0-9a-f]{8}.*"),
                "no content-derived entry name: " + found.getFirst());
    }

    /**
     * <b>Every position that wires a child, not only a record field.</b> An alias is legal wherever a type
     * is, and each of these resolves its child through the same one place, so none of them needed its own
     * rule -- which is what makes a map key work without anyone thinking about map keys.
     */
    @Test
    void anAliasIsNamedAtEveryPositionThatWiresAChild() {
        assertTrue(messages("  r => { f: [pct] }", "!r { f: [500] }").getFirst().startsWith("'pct':"));
        assertTrue(messages("  r => { f: {text => pct} }", "!r { f: { \"k\" => 500 } }")
                .getFirst().startsWith("'pct':"));
        assertTrue(messages("  r => { f: [text, pct] }", "!r { f: [x 500] }").getFirst().startsWith("'pct':"));
        assertTrue(messages("  r => { f: {pct => text} }", "!r { f: { 500 => \"v\" } }")
                .getFirst().startsWith("'pct':"));
    }

    /** And through an aliased record, where the inner field's own alias is what names the value that failed. */
    @Test
    void anAliasSurvivesADescentThroughAnotherAlias() {
        List<String> found = messages("""
                  person => { n: pct }
                  who    => person
                  r      => { f: who }""", "!r { f: { n: 500 } }");

        assertTrue(found.getFirst().startsWith("'pct':"), found.getFirst());
    }

    /**
     * <b>What must not change.</b> A built-in named directly is the author's own word for it, and a sugar
     * form already renders as what they wrote ({@code EntryDisplayName}) -- neither carries an alias, so
     * both take the shared reader back unchanged and say exactly what they said before.
     */
    @Test
    void aPositionWithNoAliasIsUntouched() {
        assertTrue(messages("  r => { g: int32 }", "!r { g: 99999999999999 }")
                .getFirst().startsWith("'int32':"));
        assertTrue(messages("  r => { h: [int32; 1..2] }", "!r { h: [1 2 3] }")
                .getFirst().startsWith("'[int32; 1..2]'"));
    }
}
