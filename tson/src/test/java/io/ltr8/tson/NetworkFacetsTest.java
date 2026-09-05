package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.schema.TsonSchemaValidationException;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §5.5's {@code within} and {@code excluding}, which were declared by four constructors and applied by none.
 *
 * <p><b>The two families read them differently, and meta.tn says so.</b> For an <em>address</em>: it "must
 * lie inside at least one {@code within} network when the field is present, and inside no {@code excluding}
 * network — {@code excluding} carves holes out of {@code within}". For a <em>network</em>: the value "must be
 * a subnet of at least one" {@code within}, and "must not overlap any listed" {@code excluding} — "overlap,
 * not containment, so a wider value cannot smuggle an excluded block".
 *
 * <p>That last clause is the one worth a test of its own: {@code 10.0.0.0/8} is refused against an excluded
 * {@code 10.1.0.0/16} not because it is inside it but because it <em>contains</em> it.
 *
 * <p>CIDR blocks are nodes of a prefix tree — nested or disjoint, never partially overlapping — so overlap is
 * exactly "one contains the other" and the arithmetic needs no interval reasoning.
 */
class NetworkFacetsTest {

    private static final String ID = "https://example.test/network-facets.tn";

    private static final String SCHEMA = """
            !!id:"%s"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              private_v4 => !ipv4 ^ { within: ["10.0.0.0/8" "192.168.0.0/16"]  excluding: ["10.1.0.0/16"] }
              subnet     => !cidr4 ^ { within: ["10.0.0.0/8"]  excluding: ["10.1.0.0/16"] }
              v6         => !ipv6 ^ { within: ["2001:db8::/32"] }
              holder => { a: private_v4?  n: subnet?  s: v6? }
            }
            """.formatted(ID);

    private static List<Diagnostic> read(String field, String value) {
        Tson tson = Tson.builder().build();
        tson.resolve(SCHEMA);
        return tson.validate("!!schema:\"" + ID + "\"\n!holder { " + field + ": \"" + value + "\" }");
    }

    private static void accepts(String field, String value) {
        List<Diagnostic> problems = read(field, value);
        assertTrue(problems.isEmpty(), value + " should be accepted: " + problems);
    }

    private static void refuses(String field, String value) {
        assertTrue(!read(field, value).isEmpty(), value + " should be refused");
    }

    /** An address inside a permitted network and outside every excluded one. */
    @Test
    void anAddressInsideWithinAndOutsideExcludingIsAccepted() {
        accepts("a", "10.2.3.4");
        accepts("a", "192.168.1.1");
    }

    /** {@code within} is a whitelist: outside every listed network is refused. */
    @Test
    void anAddressOutsideEveryWithinNetworkIsRefused() {
        refuses("a", "8.8.8.8");
    }

    /** And {@code excluding} carves holes out of it — inside {@code within}, but inside a hole. */
    @Test
    void anAddressInsideAnExcludedHoleIsRefused() {
        refuses("a", "10.1.2.3");
    }

    /** A network value must be a subnet of a permitted one, not merely overlap it. */
    @Test
    void aNetworkSubnetOfWithinIsAccepted() {
        accepts("n", "10.2.0.0/16");
    }

    @Test
    void aNetworkThatIsNoSubnetOfAnyWithinIsRefused() {
        refuses("n", "172.16.0.0/12");
    }

    /** A network inside an excluded block is refused, as an address would be. */
    @Test
    void aNetworkInsideAnExcludedBlockIsRefused() {
        refuses("n", "10.1.128.0/17");
    }

    /**
     * <b>And so is one that contains it.</b> {@code 10.0.0.0/8} is a subnet of {@code within} and holds no
     * address the excluded block does not — but it overlaps, and overlap is the rule, so a wider value cannot
     * smuggle an excluded block through.
     */
    @Test
    void aNetworkContainingAnExcludedBlockIsRefusedForOverlap() {
        refuses("n", "10.0.0.0/8");
    }

    /** The same arithmetic over sixteen octets rather than four. */
    @Test
    void theV6FamilyUsesTheSameRules() {
        accepts("s", "2001:db8:1::1");
        refuses("s", "2001:db9::1");
    }

    /**
     * A malformed entry is refused at schema load rather than surfacing as every value failing against a
     * constraint the author believes they wrote. The facets are typed {@code [value]}, so nothing in the
     * vocabulary catches it.
     */
    @Test
    void aMalformedNetworkInTheListIsRefusedAtSchemaLoad() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> Tson.builder().build().resolve("""
                        !!id:"https://example.test/bad-network.tn"
                        !!meta:"https://tson.io/2026/35/m/meta.tn"
                        !!import:"https://tson.io/2026/35/m/core.tn"
                        { oops => !ipv4 ^ { within: ["10.0.0.0" "not-a-network"] } }
                        """));

        assertTrue(thrown.getMessage().contains("not-a-network"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("10.0.0.0"), "every bad entry is named: " + thrown.getMessage());
    }

    /**
     * The pair's own emptiness rule, reached from schema text. An {@code excluding} list covering every
     * network {@code within} permits describes no value, and a body describing no value fails to load —
     * {@code { min: 10 max: 3 }}'s treatment, for the same reason.
     */
    @Test
    void aWithinAndExcludingPairThatAdmitsNothingIsRefusedAtSchemaLoad() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> Tson.builder().build().resolve("""
                        !!id:"https://example.test/empty-network-pair.tn"
                        !!meta:"https://tson.io/2026/35/m/meta.tn"
                        !!import:"https://tson.io/2026/35/m/core.tn"
                        { oops => !ipv4 ^ { within: ["10.0.0.0/8"]  excluding: ["10.0.0.0/9" "10.128.0.0/9"] } }
                        """));

        assertTrue(thrown.getMessage().contains("admit no address"), thrown.getMessage());
    }

    /**
     * And the network family folds its prefix bounds into the same question, because its value is a block:
     * every address here survives but {@code 10.0.0.5}, and the only block short enough for {@code
     * max_prefix} is the {@code within} entry itself, which overlaps the hole.
     */
    @Test
    void aNetworkFamilyPairEmptiedByItsPrefixBoundIsRefusedAtSchemaLoad() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> Tson.builder().build().resolve("""
                        !!id:"https://example.test/empty-network-bound.tn"
                        !!meta:"https://tson.io/2026/35/m/meta.tn"
                        !!import:"https://tson.io/2026/35/m/core.tn"
                        { oops => !cidr4 ^ { within: ["10.0.0.0/24"]  excluding: ["10.0.0.5/32"]
                                             max_prefix: 24 } }
                        """));

        assertTrue(thrown.getMessage().contains("admit no network"), thrown.getMessage());
    }

    /** The same body with the ceiling lifted past the largest surviving block loads and reads. */
    @Test
    void liftingTheCeilingPastTheSurvivingBlockLoadsTheSameBody() {
        Tson tson = Tson.builder().build();
        tson.resolve("""
                !!id:"https://example.test/inhabited-network-bound.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                { ok => !cidr4 ^ { within: ["10.0.0.0/24"]  excluding: ["10.0.0.5/32"]  max_prefix: 25 }
                  holder => { n: ok? } }
                """);
        String document = "!!schema:\"https://example.test/inhabited-network-bound.tn\"\n"
                + "!holder { n: \"10.0.0.128/25\" }";
        assertTrue(tson.validate(document).isEmpty(), () -> "the surviving block reads: " + tson.validate(document));
    }
}
