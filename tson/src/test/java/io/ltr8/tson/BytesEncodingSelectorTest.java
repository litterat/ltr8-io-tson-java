package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.tree.TsonValue;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * meta.tn's {@code bytes_type.encoding} selector: which RFC 4648 alphabet the text class of encodings spells
 * a {@code bytes} value in.
 *
 * <p><b>It is a facet, and that is a claim about types rather than about values.</b> The value is the octets
 * — the same four are {@code "3q2+7w=="}, {@code "deadbeef"} and {@code "32W353Y="} — and §5.7 makes a
 * selector one that picks a spelling and never changes what two values compare as. But a type does two jobs:
 * it names a value space, <em>and</em> it says which documents are valid. In a text encoding the alphabet
 * changes the second, so it belongs to the type.
 *
 * <p><b>The argument that settles it is the container element.</b> {@code [hexdigest]} can only work if the
 * element's own type carries the alphabet — an element has no annotation position of its own — so an
 * annotation could never express "an array of hex-spelled digests" without new syntax at every container.
 * Carried as a facet it costs nothing: {@code hexdigest} is a type, and a type is what an element names.
 *
 * <p>Core declares no spelled subtypes. An unrefined {@code bytes} position is base64 in every text encoding,
 * and an author who wants another alphabet refines one type and names it.
 */
class BytesEncodingSelectorTest {

    private static final String ID = "https://example.test/bytes-encoding.tn";

    private static final String SCHEMA = """
            !!id:"%s"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              hexdigest => !bytes ^ { encoding: HEX  length: 4 }
              b32       => !bytes ^ { encoding: BASE32 }
              aliased   => hexdigest
              box => <T> { value: T }

              holder => {
                plain:     bytes
                hex:       hexdigest
                b32:       b32
                via_alias: aliased
                in_array:  [hexdigest]
                in_map:    {text => hexdigest}
                in_tuple:  [hexdigest, text]
                boxed:     box<hexdigest>
              }
            }
            """.formatted(ID);

    private static final byte[] DEADBEEF = HexFormat.of().parseHex("deadbeef");

    private static Tson tson() {
        Tson tson = Tson.builder().build();
        tson.resolve(SCHEMA);
        return tson;
    }

    private static final String DOCUMENT = """
            {
              plain:     "3q2+7w=="
              hex:       "deadbeef"
              b32:       "32W353Y="
              via_alias: "deadbeef"
              in_array:  [ "deadbeef" ]
              in_map:    { k => "deadbeef" }
              in_tuple:  [ "deadbeef" "x" ]
              boxed:     { value: "deadbeef" }
            }""";

    private static TsonValue read() {
        return tson().treeReader().withSchema(ID).readAs("!holder " + DOCUMENT, "holder");
    }

    private static byte[] at(TsonValue read, String pointer) {
        return read.at(pointer).as(byte[].class).orElseThrow();
    }

    /** Four octets, spelled three ways, one value -- which is the claim the whole design rests on. */
    @Test
    void oneValueSpelledByWhicheverAlphabetTheTypeSelects() {
        TsonValue read = read();
        for (String field : List.of("/plain", "/hex", "/b32")) {
            assertArrayEquals(DEADBEEF, at(read, field), field);
        }
    }

    /**
     * <b>Every position the type reaches, including the two an annotation could not.</b> A container element
     * and a map value have no annotation position of their own, and a template argument is normalised by
     * §8.2's identity -- so all three work here only because the alphabet travels as part of the type.
     */
    @Test
    void everyPositionTypedByItReadsInItsAlphabet() {
        TsonValue read = read();
        for (String pointer : List.of("/hex", "/via_alias", "/in_array/0", "/in_map/k", "/in_tuple/0",
                "/boxed/value")) {
            assertArrayEquals(DEADBEEF, at(read, pointer), pointer);
        }
    }

    /**
     * An unrefined position is base64 -- §4, padded, and what every neighbouring format chose.
     *
     * <p>The counter-example has to be chosen with care: hex's alphabet is a subset of base64's, so any
     * even-length hex string is also well-formed base64 (and decodes to different octets, which is the
     * ambiguity the selector exists to settle). {@code "deadbe"} is six characters -- valid hex, and invalid
     * base64, whose input must be a multiple of four.
     */
    @Test
    void theDefaultIsBase64() {
        assertTrue(problems(DOCUMENT).isEmpty(), problems(DOCUMENT).toString());
        assertTrue(hasProblem(DOCUMENT.replace("plain:     \"3q2+7w==\"", "plain:     deadbe")));
    }

    /** Two types differing only in alphabet are two types, which is what a reader of either needs. */
    @Test
    void twoAlphabetsAreTwoTypes() {
        var entries = Tson.builder().build().resolve(SCHEMA).schema().entries();
        assertNotEquals(entries.get("hexdigest").body(), entries.get("b32").body());
    }

    /**
     * And an application of each is its own entry, which is what an annotation could not deliver: §8.2
     * dereferences a pure rename in an argument, so an alphabet carried outside the type would be normalised
     * away exactly here.
     */
    @Test
    void anApplicationCarriesTheAlphabetOfItsArgument() {
        assertArrayEquals(DEADBEEF, at(read(), "/boxed/value"));
    }

    /** An alias of a refined type is a pure rename of it, and reads in the alphabet its target selects. */
    @Test
    void anAliasOfARefinedTypeKeepsItsAlphabet() {
        assertArrayEquals(DEADBEEF, at(read(), "/via_alias"));
    }

    /** The selector is on the type, so a length facet counts decoded octets whatever spells them. */
    @Test
    void lengthCountsDecodedOctets() {
        assertEquals(4, at(read(), "/hex").length);
        assertTrue(hasProblem(DOCUMENT.replace("hex:       \"deadbeef\"", "hex:       \"deadbeefaa\"")));
    }

    private static List<Diagnostic> problems(String body) {
        return tson().validate("!!schema:\"" + ID + "\"\n!holder " + body);
    }

    private static boolean hasProblem(String body) {
        return !problems(body).isEmpty();
    }
}
