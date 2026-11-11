package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where an application of an open record may not stand, refused as the rules require rather than reported as a gap
 * ([TSON-SCHEMA] §4.2, §5.2, §5.3, §5.10.1), each at the declaration that wrote it and naming the declaration to write;
 * and the one place it may stand and this library does not yet close it, a type slot, still a gap.
 */
class ApplicationWhereANameStandsTest {

    private static final String HEADER = """
            !!id:"https://example.test/application-refusals.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              box       => <T> { v: T }
              pair      => <A, B> { first: A  second: B }
              wrap      => <T> { w: T }
              node      => <T> { value: T  next?: node<T> }
              pet       => abstract { kind: text =?  name: text }
              dog_of    => <T> pet & { kind?: = "dog"  breed: T }
              kennel_of => <T> { d: dog_of<T> }
              holder    => <T> { n: node<T> }
              page      => <T> { items: [T] }
              order     => { id: text }
            %s
            }
            """;

    private static List<Diagnostic> problems(String declarations) {
        return Tson.standard().validateSchema(HEADER.formatted(declarations));
    }

    /** One schema error at {@code entry}, its message holding each fragment. */
    private static void refused(String declarations, String entry, String... fragments) {
        List<Diagnostic> problems = problems(declarations);
        assertEquals(1, problems.size(), problems::toString);
        Diagnostic problem = problems.getFirst();
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problem.code(), problems::toString);
        assertEquals(Optional.of("/" + entry), problem.schemaPointer(), problems::toString);
        for (String fragment : fragments) {
            assertTrue(problem.message().contains(fragment), problem.message());
        }
    }

    // ── An argument of an open record's application is a name or a parameter (§5.3) ───────────

    @Test
    void anApplicationAsAnOpenRecordsArgumentIsRefused() {
        refused("  r => { f: pair<box<text> int32> }", "r",
                "'box<text>' is passed as an argument of 'pair<box<text> int32>'", "'text_box => box<text>'");
        refused("  w => wrap<box<text>>", "w", "'box<text>' is passed as an argument of 'wrap<box<text>>'");
        refused("  r => { f: wrap<meta.array<text>> }", "r", "'meta.array<text>' is passed as an argument");
    }

    /** A template named bare as an argument is no type, and is refused before the gap of closing in place. */
    @Test
    void aTemplateNamedBareAsAnArgumentIsRefused() {
        refused("  r => { f: wrap<box> }", "r", "'box', passed as an argument of 'wrap<box>', is a template");
    }

    /** Inside an open record too, at its own declaration, before anything applies it. */
    @Test
    void anApplicationAsAnOpenRecordsArgumentIsRefusedInAnOpenRecord() {
        refused("  o => <T> { f: pair<box<T> int32> }", "o", "'box<T>' is passed as an argument of 'pair<box<T> int32>'");
        refused("  o => <T> { f: wrap<meta.array<T>> }", "o", "'meta.array<T>' is passed as an argument");
    }

    /** An argument of a constructor's application lands at a type slot, and is held there inside an open record. */
    @Test
    void anApplicationAsAConstructorsArgumentAtATypeSlotIsHeld() {
        assertEquals(List.of(), problems("  o => <T> { f: meta.array<box<T>> }"));
    }

    // ── Where only a name may stand an application closes nowhere (§4.2) ────────────────────────

    @Test
    void anApplicationAtAReferencesOfIsRefused() {
        refused("  r => { f: meta.ref<box<text>> }", "r", "'box<text>' is an application", "only a name may stand");
        refused("  rr => meta.ref<box<text>>", "rr", "'box<text>' is an application");
        refused("  rr => !meta.reference_type { of: box<text> }", "rr", "'box<text>' is an application");
        refused("  r => { f: meta.ref<meta.array<text>> }", "r", "'meta.array<text>' is an application");
        refused("  o => <T> { f: meta.ref<box<T>> }", "o", "'box<T>' is an application");
    }

    @Test
    void anApplicationAtAMapsKeyIsRefused() {
        refused("  m => !meta.map { key_type: box<text>  value_type: text }", "m", "'box<text>' is an application");
        refused("  r => { f: {box<text> => text} }", "r", "'box<text>' is an application");
        refused("  m => !meta.map { key_type: meta.array<text>  value_type: text }", "m",
                "'meta.array<text>' is an application");
    }

    @Test
    void anApplicationAtABoundIsRefused() {
        refused("  b => <T: box<text>> { v: T }", "b", "'box<text>' is an application", "'text_box => box<text>'");
    }

    @Test
    void anApplicationAtAnAliassTargetIsRefused() {
        refused("  a => !meta.alias { target: box<text> }", "a", "'box<text>' is an application");
        refused("  a => !meta.alias { target: meta.array<text> }", "a", "'meta.array<text>' is an application");
    }

    // ── What a record in place may not be (§5.2, §5.10.1) ───────────────────────────────────────

    /** A record in place IS-A a declared type would be a member no declaration names. */
    @Test
    void anApplicationThatWouldBeAFamilyMemberInPlaceIsRefused() {
        refused("  r => { k: dog_of<text> }", "r", "'dog_of<text>' would close in place to a record IS-A 'pet'",
                "'text_dog_of => dog_of<text>'");
        assertEquals(List.of(), problems("  dogs => dog_of<text>\n  r => { k: dogs }"));
    }

    /** Held in an open record, it is refused at the declaration whose application closed it. */
    @Test
    void aHeldFamilyMemberIsRefusedWhereItsApplicationClosed() {
        refused("  kt => kennel_of<text>", "kt", "'dog_of<text>' would close in place");
    }

    /** A recursive application with no declaration closing it has nothing to tie to. */
    @Test
    void aRecursiveApplicationWithNoDeclarationIsRefused() {
        refused("  r => { f: node<text> }", "r", "recursive open record 'node'", "'text_node => node<text>'");
        refused("  h => holder<text>", "h", "recursive open record 'node'");
        assertEquals(List.of(), problems("  text_node => node<text>\n  r => { f: text_node }"));
    }

    // ── Where an application closes in place ──────────────────────────────────────────────────

    /**
     * An application of an open record at a type slot closes in place ([TSON-SCHEMA] §5.10): at a field, inside a
     * construction, and left in an open record's body by applying it. None is a refusal or a gap.
     */
    @Test
    void anApplicationAtATypeSlotClosesInPlace() {
        for (String declarations : List.of("  r => { f: page<order> }", "  r => { f: [box<text>] }",
                "  pg => <T> { cursor: box<T> }\n  pt => pg<text>")) {
            assertEquals(List.of(), problems(declarations), declarations);
        }
    }
}
