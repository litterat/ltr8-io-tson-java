package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Scale is not part of a {@code number}'s value, so two spellings of one number are one value everywhere a
 * value is compared.
 *
 * <p>[TSON-SCHEMA] §5.5 puts the value space outside the lexical space and [TSON-JSON] §5.3 states the
 * consequence outright — "{@code 199.90} and {@code 199.9} are one {@code number}, and every comparison,
 * bound and {@code members} check is over that one value". [TSON-DATA] §2.6 says the same for keys, and
 * [TSON-SCHEMA] §7.5 for set members.
 *
 * <p><b>Three sites compare decoded values and all three go through one place</b>, so the rule is asked once
 * and answered once: a duplicate map key (§2.6), a repeated set element (§7.5), and a written value at a
 * FIXED field (§5.2). The last is the one that turns away a conforming document rather than admitting a
 * malformed one, which is why it is the one most likely to be met by a real sender.
 */
class NumberIdentityTest {

    private static final String ID = "https://example.test/identity-1.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/identity-1.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
            {
              by_number  => { number => text }
              number_set => set<number>
              pinned     => { rate: number = 1.0  label: text }
              scaled     => { rate: number = 199.90  label: text }
            }
            """;

    private static final Tson TSON = Tson.of(ProcessorConfig.defaults()
            .withSchemaAccess(SchemaAccess.of((SchemaSource) uri -> SCHEMA)));

    private static List<Diagnostic> validate(String rootType, String body) {
        return TSON.validate("!!schema:\"%s\"\n!%s %s".formatted(ID, rootType, body));
    }

    private static List<Diagnostic.Code> codes(String rootType, String body) {
        return validate(rootType, body).stream().map(Diagnostic::code).toList();
    }

    // ── §2.6: a map states each key at most once ─────────────────────────

    @Test
    void twoSpellingsOfOneNumberAreOneKey() {
        assertEquals(List.of(Diagnostic.Code.DUPLICATE_MAP_KEY), codes("by_number", """
                { 1 => "a"  1.0 => "b" }"""));
    }

    @Test
    void scaleIsNotPartOfTheKeyHoweverManyZeros() {
        assertEquals(List.of(Diagnostic.Code.DUPLICATE_MAP_KEY), codes("by_number", """
                { 199.90 => "a"  199.9 => "b" }"""));
    }

    /** Two genuinely different numbers stay two keys, so the rule narrows nothing it should not. */
    @Test
    void differentNumbersAreStillDifferentKeys() {
        assertEquals(List.of(), codes("by_number", """
                { 1 => "a"  2 => "b" }"""));
    }

    // ── §7.5: a set asserts uniqueness over the element type's value space ──

    @Test
    void twoSpellingsOfOneNumberAreOneSetMember() {
        assertEquals(List.of(Diagnostic.Code.TYPE_MISMATCH), codes("number_set", "[ 1, 1.0 ]"));
    }

    @Test
    void differentNumbersAreStillDifferentSetMembers() {
        assertEquals(List.of(), codes("number_set", "[ 1, 2 ]"));
    }

    // ── §5.2: a written value at a FIXED field is verified against the pin ──

    /**
     * The case that turns away a conforming document: the schema pins {@code 1.0} and the document writes
     * {@code 1}, which is the same number.
     */
    @Test
    void aFixedNumberAcceptsAnotherSpellingOfItself() {
        assertEquals(List.of(), codes("pinned", """
                { rate: 1  label: "x" }"""));
    }

    @Test
    void aFixedNumberAcceptsItsOwnSpelling() {
        assertEquals(List.of(), codes("pinned", """
                { rate: 1.0  label: "x" }"""));
    }

    @Test
    void aFixedNumberStillRefusesADifferentNumber() {
        assertEquals(List.of(Diagnostic.Code.FIELD_FIXED), codes("pinned", """
                { rate: 2  label: "x" }"""));
    }

    /** And the trailing zeros a `number` preserves for re-encoding do not make it a different value. */
    @Test
    void aFixedNumberIgnoresTrailingZerosInEitherDirection() {
        assertEquals(List.of(), codes("scaled", """
                { rate: 199.9  label: "x" }"""));
        assertEquals(List.of(), codes("scaled", """
                { rate: 199.900  label: "x" }"""));
    }
}
