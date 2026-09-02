package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.TsonTreeWriter;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Absence has one spelling, {@code _}, and this pins it end to end -- schemaless and under a schema, reading
 * and writing. It is the class that would notice a second spelling growing back.
 *
 * <p><b>{@code null} is not that spelling.</b> §4 resolves boolean, then number, then string, so the unquoted
 * token {@code null} is the string {@code null} the way {@code frobnicate} is: at a {@code text} position it
 * is text, at an {@code int32} position a type error, and at a {@code void} position an error too, {@code
 * void} admitting the sentinel and nothing else. A JSON document's {@code null} reaches absence through a
 * JSON reader, which maps it in the model, where the position's own state decides whether absence is
 * admitted at all.
 *
 * <p><b>What {@code _} means is [TSON-DATA] §2.9's distinction</b>, and the tree keeps it: a field written
 * {@code _} is present with an absent value, and a field never written is not there.
 */
class AbsenceHasOneSpellingTest {

    private static final String ID = "https://example.test/person-1.tn";
    private static final String SCHEMA = """
            !!id:"https://example.test/person-1.tn"
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
            {
              person => {
                name: text
                nickname: text?
                deleted: void?
              }
            }
            """;

    private static Tson tson() {
        TsonSchemaSource source = uri -> {
            String base = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
            if (base.equals(ID)) {
                return SCHEMA;
            }
            throw new IllegalStateException("no schema for " + uri);
        };
        return Tson.builder().schemaSource(source).build();
    }

    private static TsonValue readPerson(String fields) {
        return tson().treeReader().read("""
                !!schema:"https://example.test/person-1.tn"
                !person { name: "Ada"  %s }""".formatted(fields));
    }

    // ── Schemaless: §4 applies, and it resolves three classes ──

    @Test
    void schemalessNullIsAStringAndOnlyTheSentinelIsAbsent() {
        TsonValue node = tson().treeReader().read("{ a: null  b: _  c: \"null\" }");

        assertEquals(Optional.of("null"), node.at("/a").asString());
        assertTrue(node.at("/b").isAbsent());
        assertEquals(Optional.of("null"), node.at("/c").asString(), "quoted or not, it is the same string");
    }

    /** Each writes back as what it is: a string in the writer's uniform quoted form, and the sentinel bare. */
    @Test
    void schemalessEachSpellingWritesBackAsWhatItIs() {
        TsonValue node = tson().treeReader().read("{ a: null  b: _ }");

        assertEquals("{ a: \"null\" b: _ }", new TsonTreeWriter().toTson(node));
    }

    // ── Under a schema: a field written `_` is present, and one never written is not ──

    /**
     * <b>[TSON-DATA] §2.9's distinction, kept by the tree.</b> "A field or entry set to {@code _} is present
     * with an absent value — distinct from not appearing at all." Both readings used to produce a tree with
     * no {@code nickname} at all, so a document that said something about the field and one that said nothing
     * read alike — the record being the one container of the four that dropped it, where an array element and
     * a tuple slot already kept it.
     *
     * <p>{@code get} answers the two apart without throwing, which is the whole of the tree model's
     * navigation contract: a written {@code _} is a {@code TsonAbsent} that is present, and a field never
     * written is a {@code TsonMissing} carrying the pointer of the step that failed.
     *
     * <p><b>Bind mode still collapses them</b>, and deliberately: a Java component has no third state between
     * "set to nothing" and "never set", so both readings arrive as {@code null}. That is a limit of the
     * target rather than a reading of §2.9 -- {@code RecordBindReader.statedAbsentValue} says so -- and it is
     * why the tree's answer is a subclass's rather than one shared between them.
     */
    @Test
    void underASchemaAFieldWrittenAbsentIsPresentAndOneNeverWrittenIsNot() {
        TsonValue written = readPerson("nickname: _");
        TsonValue omitted = readPerson("");

        assertTrue(written.get("nickname").isAbsent(),
                () -> "written '_': present with an absent value -- " + written);
        assertTrue(omitted.get("nickname").isMissing(),
                () -> "never written: not there at all -- " + omitted);
        assertFalse(written.get("nickname").isMissing(), written::toString);
    }

    /** And it survives the round trip, which is what makes the distinction usable rather than merely read. */
    @Test
    void underASchemaAFieldWrittenAbsentWritesBackAsTheSentinel() {
        String rewritten = new TsonTreeWriter().toTson(readPerson("nickname: _"));

        assertTrue(rewritten.contains("nickname: _"), rewritten);
        assertFalse(new TsonTreeWriter().toTson(readPerson("")).contains("nickname"),
                "and a field never written stays out of the output");
    }

    // ── Under a schema: §7.3, the position's own type decides ──

    /**
     * A {@code text} position reads {@code null} as the text it is. A JSON converter aiming this at an
     * OPTIONAL {@code text?} field gets the string, not absence -- which is the case that argues for routing
     * such input through a JSON reader rather than for a keyword in the notation.
     */
    @Test
    void underASchemaNullAtATextPositionIsTheStringNull() {
        assertEquals(Optional.of("null"), readPerson("nickname: null").at("/nickname").asString());
    }

    /**
     * A {@code void} position is where a second spelling would be cheapest to admit -- the type has one
     * inhabitant, so nothing is lost by conceding -- and it is refused there too. Conceding is what would make
     * absence's spelling depend on the position's type, which is a rule an author computes rather than
     * remembers. {@code VoidReaderTest} pins the same refusal at the reader level.
     */
    @Test
    void underASchemaNullAtAVoidPositionIsAnError() {
        TsonReadException thrown =
                assertThrows(TsonReadException.class, () -> readPerson("deleted: null"));

        assertEquals(Diagnostic.Code.TYPE_MISMATCH, thrown.diagnostic().code());
    }

    /** And absence is still absence -- a REQUIRED field written {@code _} fails as it always did. */
    @Test
    void underASchemaTheSentinelAtARequiredPositionStillFails() {
        TsonReadException thrown = assertThrows(TsonReadException.class, () -> tson().treeReader().read("""
                !!schema:"https://example.test/person-1.tn"
                !person { name: _  nickname: "Countess" }"""));

        assertEquals(Diagnostic.Code.FIELD_REQUIRED, thrown.diagnostic().code());
    }
}
