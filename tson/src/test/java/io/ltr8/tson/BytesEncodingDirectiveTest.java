package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.tree.TsonValue;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * meta.tn's {@code @bytes_encoding} directive: which RFC 4648 alphabet a text encoding spells a {@code
 * bytes} value in.
 *
 * <p><b>It is a directive, not a facet, and that is the whole point.</b> A spelling is not a kind of value:
 * the same octets are {@code "3q2+7w=="}, {@code "deadbeef"} and {@code "3WV37Q======"}. Carrying the
 * alphabet as a facet made four types over one value space related by an IS-A that narrowed nothing;
 * carrying it as a type-ref made a lexical fact into a type claim. As a directive it neither narrows the
 * type nor participates in identity, and an encoding whose values are octets ignores it -- which could not
 * even be stated while the alphabet was part of the type.
 *
 * <p>Resolved nearest-first: the field, then the field's type walking its supertypes, then base64.
 */
class BytesEncodingDirectiveTest {

    private static final String ID = "https://example.test/bytes-encoding.tn";

    private static final String SCHEMA = """
            !!id:"%s"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              @bytes_encoding:HEX
              digest => !bytes ^ { length: 4 }

              sized => !bytes ^ { length: 4 }

              @bytes_encoding:HEX
              @doc:"A digest, spelled in hex, declared as a pure alias."
              digest_alias => bytes

              digest_chain => digest_alias

              holder => {
                plain:    bytes
                inherits: digest
                @bytes_encoding:HEX
                stated:   bytes
                @bytes_encoding:BASE64
                overrides: digest
                still_hex: sized
                aliased:   digest_alias?
                chained:   digest_chain?
                in_array:  [digest_alias]?
                in_map:    {text => digest_alias}?
              }
            }
            """.formatted(ID);

    private static Tson tson() {
        Tson tson = Tson.builder().build();
        tson.resolve(SCHEMA);
        return tson;
    }

    private static TsonValue read(String body) {
        return tson().treeReader().withSchema(ID).readAs("!holder " + body, "holder");
    }

    private static final byte[] DEADBEEF = HexFormat.of().parseHex("deadbeef");

    /** Four bytes, spelled four ways, one value -- which is the claim the whole design rests on. */
    @Test
    void oneValueSpelledByWhicheverDirectiveIsNearest() {
        TsonValue read = read("""
                {
                  plain:     "3q2+7w=="
                  inherits:  "deadbeef"
                  stated:    "deadbeef"
                  overrides: "3q2+7w=="
                  still_hex: "3q2+7w=="
                }""");

        for (String field : List.of("plain", "inherits", "stated", "overrides", "still_hex")) {
            assertArrayEquals(DEADBEEF, read.get(field).as(byte[].class).orElseThrow(), field);
        }
    }

    /**
     * An unannotated position is base64 -- §4, padded, and what every neighbouring format chose.
     *
     * <p>The counter-example has to be chosen with care: hex's alphabet is a subset of base64's, so any
     * even-length hex string is also well-formed base64 (and decodes to different octets, which is the
     * ambiguity the directive exists to settle). {@code "deadbe"} is six characters -- valid hex, and
     * invalid base64, whose input must be a multiple of four.
     */
    @Test
    void theDefaultIsBase64() {
        assertTrue(problems("{ plain: \"3q2+7w==\"  inherits: deadbeef  stated: deadbeef "
                + " overrides: \"3q2+7w==\"  still_hex: \"3q2+7w==\" }").isEmpty());
        assertTrue(hasProblem("{ plain: deadbe  inherits: deadbeef  stated: deadbeef "
                + " overrides: \"3q2+7w==\"  still_hex: \"3q2+7w==\" }"),
                "an unannotated bytes position reads base64, and 'deadbe' is not well-formed base64");
    }

    /**
     * The directive on a <em>declaration</em> reaches every use of it, and a refinement inherits it up its
     * supertype chain -- {@code digest} is annotated, and a field typed by it needs no directive of its own.
     */
    @Test
    void aDeclarationsDirectiveReachesEveryFieldTypedByIt() {
        assertTrue(hasProblem("{ plain: \"3q2+7w==\"  inherits: \"3q2+7w==\"  stated: deadbeef "
                + " overrides: \"3q2+7w==\"  still_hex: \"3q2+7w==\" }"),
                "'inherits' is typed by a HEX-annotated declaration, so base64 text is not its spelling");
    }

    /** And a field's own directive wins over the one its type carries, which is what nearest-first means. */
    @Test
    void aFieldsOwnDirectiveOverridesItsTypes() {
        assertTrue(hasProblem("{ plain: \"3q2+7w==\"  inherits: deadbeef  stated: deadbeef "
                + " overrides: deadbeef  still_hex: \"3q2+7w==\" }"),
                "'overrides' is a HEX-typed field annotated BASE64, so the field's own directive decides");
    }

    /** The alphabet is no part of the type, so two fields of one type may be spelled differently. */
    @Test
    void theDirectiveDoesNotChangeTheType() {
        var entries = tson().bindRegistry().core().resolveLinked(ID).schema().entries();

        assertEquals(entries.get("sized").body(), entries.get("digest").body(),
                "an annotated declaration and an unannotated one with the same facets are the same type");
    }

    private static List<Diagnostic> problems(String body) {
        return tson().validate("!!schema:\"" + ID + "\"\n!holder " + body);
    }

    private static boolean hasProblem(String body) {
        return !problems(body).isEmpty();
    }

    /**
     * The case the alias hole cost: two spellings of one intent -- "a named hex digest" -- and before §8.3's
     * walk carried the directive, only the refinement got it. The alias read base64 and said nothing.
     */
    @Test
    void anAliasDirectsItsSpellingAsARefinementDoes() {
        TsonValue read = read("""
                {
                  plain:     "3q2+7w=="
                  inherits:  "deadbeef"
                  stated:    "deadbeef"
                  overrides: "3q2+7w=="
                  still_hex: "3q2+7w=="
                  aliased:   "deadbeef"
                  chained:   "deadbeef"
                  in_array:  [ "deadbeef" ]
                  in_map:    { k => "deadbeef" }
                }
                """);
        assertArrayEquals(DEADBEEF, read.get("aliased").as(byte[].class).orElseThrow());
        assertArrayEquals(DEADBEEF, read.get("inherits").as(byte[].class).orElseThrow(),
                "the refinement spelling must still agree with the alias spelling");
    }

    /** A chain of aliases carries from the hop that wrote the directive, not only from the name at the use site. */
    @Test
    void aChainOfAliasesCarriesFromTheHopThatWroteIt() {
        TsonValue read = read("""
                {
                  plain: "3q2+7w=="  inherits: "deadbeef"  stated: "deadbeef"  overrides: "3q2+7w=="
                  still_hex: "3q2+7w=="  aliased: "deadbeef"  chained: "deadbeef"
                  in_array: [ "deadbeef" ]  in_map: { k => "deadbeef" }
                }
                """);
        assertArrayEquals(DEADBEEF, read.get("chained").as(byte[].class).orElseThrow());
    }

    /**
     * Every position reads it, not only a record field. An array element and a map value have no field of
     * their own to state a directive on, so an alias is the only way they can get one at all -- which is what
     * makes the use-site seam ({@code UseSite.Respelled}) the right place for it rather than the record reader.
     */
    @Test
    void aContainerElementReadsTheAliasDirective() {
        TsonValue read = read("""
                {
                  plain: "3q2+7w=="  inherits: "deadbeef"  stated: "deadbeef"  overrides: "3q2+7w=="
                  still_hex: "3q2+7w=="  aliased: "deadbeef"  chained: "deadbeef"
                  in_array: [ "deadbeef" ]  in_map: { k => "deadbeef" }
                }
                """);
        assertArrayEquals(DEADBEEF, read.at("/in_array/0").as(byte[].class).orElseThrow());
        assertArrayEquals(DEADBEEF, read.at("/in_map/k").as(byte[].class).orElseThrow());
    }

    /**
     * Nothing is copied to the use site to make this work. The field names the alias, the alias keeps its own
     * annotations, and the directive is applied where the alias is compiled -- so resolved output carries one
     * statement of the fact rather than a copy of it at every position that reaches the type.
     */
    @Test
    void theDirectiveStaysOnTheDeclarationRatherThanTravelling() {
        var entries = Tson.builder().build().resolve(SCHEMA).schema().entries();
        var holder = (io.ltr8.tson.schema.meta.RecordBody) entries.get("holder").body();
        var aliased = holder.fields().stream().filter(f -> f.name().equals("aliased")).findFirst().orElseThrow();

        assertEquals("digest_alias", aliased.type().name(), "the use site names what the author wrote");
        assertTrue(aliased.type().annotations().isEmpty(), "and carries nothing of the declaration's");
        assertEquals(List.of("bytes_encoding", "doc"),
                entries.getAnnotations("digest_alias").values().stream()
                        .map(io.ltr8.annotation.Annotation::name).toList(),
                "which is where both of them stay");
    }
}
