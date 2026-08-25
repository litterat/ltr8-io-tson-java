package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.TsonTreeWriter;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the {@code null} token means absence and where it does not -- the boundary [TSON-DATA] §4's own
 * applicability clause draws, pinned end to end because it is easy to move by accident in either direction.
 *
 * <p><b>Schemaless (§4 applies).</b> Base type resolution identifies {@code null} and the tree model spells
 * the result {@link io.ltr8.tson.tree.TsonAbsent} -- there is one no-value node, not a separate null node
 * beside it. {@code _} and {@code null} are therefore indistinguishable once read, and both write back as
 * {@code _}.
 *
 * <p><b>Under a schema (§4 does not apply).</b> [TSON-SCHEMA] §7.3: "The tokens {@code true}, {@code false},
 * and {@code null} have no special status when a schema is in scope -- their meaning is determined entirely
 * by the position's type." So {@code null} at a {@code text} position is the string {@code "null"}, and the
 * sole exception is a {@code void}-typed position, where §7.3 accepts it as a spelling of {@code _} on the
 * grounds that {@code void} has a single inhabitant and so loses no distinction.
 */
class NullIsAbsentTest {

    private static final String ID = "https://example.test/person-1.tn";
    private static final String SCHEMA = """
            !!id:"https://example.test/person-1.tn"
            !!meta:"https://tson.io/2026/33/m/meta.tn"
            !!import:"https://tson.io/2026/33/m/core.tn"
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

    // ── Schemaless: §4 applies, and the tree has one no-value node ──

    @Test
    void schemalessNullAndTheSentinelBothReadAsAbsent() {
        TsonValue node = tson().treeReader().read("{ a: null  b: _ }");

        assertTrue(node.at("/a").isAbsent());
        assertTrue(node.at("/b").isAbsent());
        assertEquals(node.at("/b"), node.at("/a"), "one no-value node, so the two spellings are one value");
    }

    /** §4.4 is untouched: a quoted {@code "null"} is an ordinary string, schemaless or not. */
    @Test
    void schemalessQuotedNullIsStillTheStringNull() {
        assertEquals(Optional.of("null"), tson().treeReader().read("{ a: \"null\" }").at("/a").asString());
    }

    /** Absence has one written form, so a schemaless {@code null} normalizes to {@code _} on the way out. */
    @Test
    void schemalessNullWritesBackAsTheSentinel() {
        TsonValue node = tson().treeReader().read("{ a: null }");

        assertEquals("{ a: _ }", new TsonTreeWriter().toTson(node));
    }

    // ── Under a schema: §7.3, the position's own type decides ──

    /**
     * The rule that makes the schemaless behaviour safe to have: {@code null} is <em>not</em> special under a
     * schema, so a {@code text} position reads it as the text it is. A JSON converter aiming this at an
     * OPTIONAL {@code text?} field gets the string, not absence -- deliberate, and the reason §7.3 carves out
     * {@code void} rather than widening the concession.
     */
    @Test
    void underASchemaNullAtATextPositionIsTheStringNull() {
        assertEquals(Optional.of("null"), readPerson("nickname: null").at("/nickname").asString());
    }

    /**
     * §7.3's sole concession: at a {@code void}-typed position {@code null} is accepted as a spelling of
     * {@code _} and normalized to absence, where at any other position it would be handed to the declared
     * atom (and, at {@code text}, be a string).
     *
     * <p>It reads as a present {@link io.ltr8.tson.tree.TsonAbsent} rather than dropping out of the record
     * the way a stated {@code _} does. That difference is the record reader's own OPTIONAL-field handling --
     * it answers a stated {@code _} itself, before the field's reader runs -- and predates this concession;
     * both spellings mean absence either way. {@code VoidReaderTest} pins the equivalence at the reader
     * level, where the record's short-circuit isn't in the way.
     */
    @Test
    void underASchemaNullAtAVoidPositionIsAbsence() {
        assertTrue(readPerson("deleted: null").at("/deleted").isAbsent());
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
