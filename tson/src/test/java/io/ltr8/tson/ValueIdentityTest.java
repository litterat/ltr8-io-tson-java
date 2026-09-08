package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One equality contract, asked by the three rules that compare two decoded values: [TSON-SCHEMA] §7.5's
 * duplicate rule, §5.2's check of a stated FIXED value, and [TSON-DATA] §2.6's map-key identity.
 *
 * <p>{@code bytes} is where having no contract showed, because {@code byte[]} carries Java's identity
 * equality: no two decoded binary values were ever equal, so a set admitted every duplicate there is, a map
 * admitted a key twice, and a FIXED field rejected the one value it exists to accept. The last is the shape
 * worth remembering — an absent comparison is not always a missing verdict, and here it was an inverted one.
 */
class ValueIdentityTest {

    private static final String ID = "https://example.test/identity.tn";
    private static final String SCHEMA = """
            !!id:"https://example.test/identity.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              digests => set<bytes>
              tags    => set<text>
              by_hash => { bytes => text }

              hex_digest => !bytes_type { encoding: HEX  length: 2 }
              hexes   => set<hex_digest>

              stamped => { k: bytes = "SGk=" }
              holder  => { d: digests  t: tags  m: by_hash }
              hexed   => { h: hexes }

              instants => set<datetime>
              clocks   => set<time>
              logged   => { datetime => text }
              dated    => { i: instants  c: clocks  l: logged }
              due      => { at: datetime = "2026-01-01T09:00:00Z" }
              alarm    => { at: time = "01:30:00Z" }
            }
            """;

    private static Tson tson() {
        Tson tson = Tson.standard();
        tson.resolve(SCHEMA);
        return tson;
    }

    private static List<Diagnostic> read(String body) {
        return tson().validate("!!schema:\"" + ID + "\"\n" + body);
    }

    // ── §7.5, a set ──────────────────────────────────────────────────────

    @Test
    void aSetOfBytesRefusesTheSameValueTwice() {
        assertEquals(List.of(), read("!holder { d: [ \"SGk=\" \"SGVsbG8=\" ]  t: [ \"a\" ]  m: {} }"));

        List<Diagnostic> refused = read("!holder { d: [ \"SGk=\" \"SGk=\" ]  t: [ \"a\" ]  m: {} }");
        assertEquals(1, refused.size(), refused.toString());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused.getFirst().code());
        assertEquals("/d/1", refused.getFirst().path().orElseThrow());
    }

    @Test
    void aSetComparesOctetsAndNotTheSpellingThatCarriedThem() {
        // Hex is case-insensitive by every definition of it, and `HexFormat` decodes both -- so "abcd" and
        // "ABCD" are one octet string and a set holds one of them. The alphabet is the type's own `encoding`
        // selector, which picks a spelling and never changes what two values compare as (§5.7).
        assertEquals(List.of(), read("!hexed { h: [ \"abcd\" \"ef01\" ] }"));

        List<Diagnostic> refused = read("!hexed { h: [ \"abcd\" \"ABCD\" ] }");
        assertEquals(1, refused.size(), refused.toString());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused.getFirst().code());
    }

    @Test
    void aSetOfTextComparesAsAMapKeyDoes() {
        // Two NFC spellings of one string. `MapAbstractReader` normalised a key and `ArrayAbstractReader`
        // compared raw, so one document could state the same text twice in a set and not in a map.
        List<Diagnostic> refused = read("!holder { d: [ \"SGk=\" ]  t: [ \"caf\\u00e9\" \"cafe\\u0301\" ]  m: {} }");
        assertEquals(1, refused.size(), refused.toString());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused.getFirst().code());
    }

    // ── §2.6, a map key ──────────────────────────────────────────────────

    @Test
    void aMapRefusesOneBinaryKeyStatedTwice() {
        assertEquals(List.of(), read("!holder { d: [ \"SGk=\" ]  t: [ \"a\" ]  m: { \"SGk=\" => x  \"SGVsbG8=\" => y } }"));

        List<Diagnostic> refused = read("!holder { d: [ \"SGk=\" ]  t: [ \"a\" ]  m: { \"SGk=\" => x  \"SGk=\" => y } }");
        assertEquals(1, refused.size(), refused.toString());
        assertEquals(Diagnostic.Code.DUPLICATE_MAP_KEY, refused.getFirst().code());
    }

    // ── §5.2, a FIXED value ──────────────────────────────────────────────

    @Test
    void aFixedBinaryFieldAcceptsTheValueItDeclares() {
        // The sharp one. `Objects.equals` over two `byte[]` decodes is false however right the document is,
        // so this field rejected every document ever written against it, including this one.
        assertEquals(List.of(), read("!stamped { k: \"SGk=\" }"));
    }

    @Test
    void aFixedBinaryFieldStillRefusesAnotherValue() {
        List<Diagnostic> refused = read("!stamped { k: \"SGVsbG8=\" }");
        assertEquals(1, refused.size(), refused.toString());
        assertEquals(Diagnostic.Code.FIELD_FIXED, refused.getFirst().code());
    }

    // ── what a diagnostic says about octets ──────────────────────────────

    @Test
    void aRefusedBinaryValueIsNamedByItsOctetsNotItsIdentityHash() {
        // `byte[]` inherits Object.toString, so these read `[B@6d06d69c` -- an identity hash that names
        // nothing and differs between two runs over one document. It has not mattered until now, both
        // comparisons having been unreachable.
        Diagnostic duplicate = read("!holder { d: [ \"SGk=\" \"SGk=\" ]  t: [ \"a\" ]  m: {} }").getFirst();
        assertEquals("4869 (hex)", duplicate.actual());

        Diagnostic fixed = read("!stamped { k: \"SGVsbG8=\" }").getFirst();
        assertEquals("4869 (hex)", fixed.expected());
        assertEquals("48656c6c6f (hex)", fixed.actual());
        assertTrue(duplicate.message().contains("4869 (hex)"), duplicate.message());
    }

    // ── §5.5, the offset is a spelling ───────────────────────────────────

    @Test
    void aSetOfDatetimesComparesTheInstantAndNotTheOffset() {
        assertEquals(List.of(), read("!dated { i: [ \"2026-01-01T09:00:00Z\" \"2026-01-01T10:00:00Z\" ]  c: [ \"00:00:00Z\" ]  l: {} }"));

        // §5.5: "2026-01-01T10:00:00+01:00 and 2026-01-01T09:00:00Z are one value".
        List<Diagnostic> refused =
                read("!dated { i: [ \"2026-01-01T10:00:00+01:00\" \"2026-01-01T09:00:00Z\" ]  c: [ \"00:00:00Z\" ]  l: {} }");
        assertEquals(1, refused.size(), refused.toString());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused.getFirst().code());
        assertEquals("/i/1", refused.getFirst().path().orElseThrow());
    }

    @Test
    void anOffsetOfMinusZeroIsTheSameInstantAsZ() {
        // §5.5, citing RFC 3339 §4.3: -00:00 means the offset is unknown, not that it is a distinct one.
        // A guard rather than a demonstration: `ZoneOffset.of("-00:00")` is `ZoneOffset.UTC`, so this pair
        // compared equal even when the offset was the comparison. It is pinned because §5.5 states it.
        List<Diagnostic> refused =
                read("!dated { i: [ \"2026-01-01T09:00:00-00:00\" \"2026-01-01T09:00:00Z\" ]  c: [ \"00:00:00Z\" ]  l: {} }");
        assertEquals(1, refused.size(), refused.toString());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused.getFirst().code());
    }

    @Test
    void aSetOfTimesComparesTheTimeOfDayInUtc() {
        assertEquals(List.of(), read("!dated { i: [ \"2026-06-01T00:00:00Z\" ]  c: [ \"01:30:00Z\" \"02:30:00Z\" ]  l: {} }"));

        // §5.5: "23:30:00-02:00 is 01:30:00Z" -- the wrap past midnight is the point.
        List<Diagnostic> refused = read("!dated { i: [ \"2026-06-01T00:00:00Z\" ]  c: [ \"23:30:00-02:00\" \"01:30:00Z\" ]  l: {} }");
        assertEquals(1, refused.size(), refused.toString());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused.getFirst().code());
        assertEquals("/c/1", refused.getFirst().path().orElseThrow());
    }

    @Test
    void aMapRefusesOneInstantStatedInTwoOffsets() {
        assertEquals(List.of(),
                read("!dated { i: [ \"2026-06-01T00:00:00Z\" ]  c: [ \"00:00:00Z\" ]  l: { \"2026-01-01T09:00:00Z\" => a  \"2026-01-01T10:00:00Z\" => b } }"));

        List<Diagnostic> refused =
                read("!dated { i: [ \"2026-06-01T00:00:00Z\" ]  c: [ \"00:00:00Z\" ]  l: { \"2026-01-01T10:00:00+01:00\" => a  \"2026-01-01T09:00:00Z\" => b } }");
        assertEquals(1, refused.size(), refused.toString());
        assertEquals(Diagnostic.Code.DUPLICATE_MAP_KEY, refused.getFirst().code());
    }

    @Test
    void aFixedDatetimeFieldAcceptsAnotherSpellingOfItsOwnInstant() {
        assertEquals(List.of(), read("!due { at: \"2026-01-01T09:00:00Z\" }"));
        assertEquals(List.of(), read("!due { at: \"2026-01-01T10:00:00+01:00\" }"));
    }

    @Test
    void aFixedTimeFieldAcceptsAnotherSpellingOfItsOwnTimeOfDay() {
        assertEquals(List.of(), read("!alarm { at: \"01:30:00Z\" }"));
        assertEquals(List.of(), read("!alarm { at: \"23:30:00-02:00\" }"));
    }

    @Test
    void aFixedDatetimeFieldStillRefusesAnotherInstant() {
        List<Diagnostic> refused = read("!due { at: \"2026-01-01T09:00:01Z\" }");
        assertEquals(1, refused.size(), refused.toString());
        assertEquals(Diagnostic.Code.FIELD_FIXED, refused.getFirst().code());
    }
}
