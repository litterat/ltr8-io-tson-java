package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaFetchException;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sparse numeric member set end to end: {@code !integer ^ { members: [...] }} and its {@code !number}
 * twin, the spelling §7.4 leaves for a value set that is neither a contiguous range nor an arithmetic
 * progression (an HTTP status subset, a protobuf enum's explicit numbers).
 *
 * <p>Membership is [TSON-DATA] §4.3's identity -- the value denoted, not the spelling -- which is the half a
 * per-facet unit test cannot show, since the equivalence is applied by the token's own decoding on the way
 * in. {@code 0x50} reaching a member set written {@code 80} is the whole point of the rule.
 */
class SparseMemberSetTest {

    private static final String ID = "https://example.test/ports-1.tn";
    private static final String SCHEMA = """
            !!id:"https://example.test/ports-1.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              port => !integer ^ { members: [80 443 8080] }
              price => !number ^ { members: [1 2.50] }
              listing => { p: port  q: price }
            }
            """;

    private static Tson tson() {
        TsonSchemaSource source = uri -> {
            String base = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
            if (base.equals(ID)) {
                return SCHEMA;
            }
            throw new TsonSchemaFetchException(uri, TsonSchemaFetchException.Reason.NOT_FOUND,
                    "this fixture serves only " + ID, null);
        };
        return Tson.builder().schemaSource(source).build();
    }

    private static List<Diagnostic> validate(String body) {
        return tson().validate("!!schema:\"" + ID + "\"\n" + body);
    }

    @Test
    void theSchemaItselfLoads() {
        assertEquals(List.of(), tson().validateSchema(SCHEMA));
    }

    @Test
    void aMemberIsAdmittedAndAValueBetweenMembersIsNot() {
        assertEquals(List.of(), validate("!listing { p: 443  q: 2.50 }"));

        List<Diagnostic> refused = validate("!listing { p: 22  q: 1 }");
        assertEquals(1, refused.size(), refused.toString());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, refused.getFirst().code());
        assertEquals("one of (80, 443, 8080)", refused.getFirst().expected());
        assertEquals("/p", refused.getFirst().path().orElseThrow());
    }

    @Test
    void membershipIsTheValueDenotedNotTheSpelling() {
        // §4.3: 0x50 is 80, and 1.00 is the decimal member written 1 -- neither spelling appears in the set.
        assertEquals(List.of(), validate("!listing { p: 0x50  q: 1.00 }"));
        assertEquals(List.of(), validate("!listing { p: 0b1_1111_1001_0000  q: 2.5 }"));
    }

    @Test
    void aRefusedDecimalMemberIsAVerdictOnTheDocument() {
        List<Diagnostic> refused = validate("!listing { p: 80  q: 3 }");
        assertEquals(1, refused.size(), refused.toString());
        assertEquals("one of (1, 2.50)", refused.getFirst().expected());
        assertTrue(refused.getFirst().code().verdict(), "a member set is a rule about the document");
    }

    @Test
    void anAdmittedValueStillReadsBackAsWritten() {
        // The set decides admission; it never rewrites the value to whichever spelling the schema used.
        TsonValue read = tson().treeReader().read("!!schema:\"" + ID + "\"\n!listing { p: 0x50  q: 2.5 }");
        assertEquals(BigInteger.valueOf(80), read.at("/p").as(BigInteger.class).orElseThrow());
        assertEquals(new BigDecimal("2.5"), read.at("/q").as(BigDecimal.class).orElseThrow());
    }

    @Test
    void aMemberOutsideTheBodysOwnBoundsIsASchemaError() {
        // §5.6: every member must satisfy the facets beside it -- `AtomCoherence.checkMembers`, reached here
        // through a bound whose comparison the member has to be a decimal to survive at all.
        List<Diagnostic> refused = tson().validateSchema(schemaDeclaring("!number ^ { min: 1  max: 10  members: [1 80] }"));
        assertEquals(1, refused.size(), refused.toString());
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, refused.getFirst().code());
        assertTrue(refused.getFirst().message().contains("member 80 is above"), refused.getFirst().message());
    }

    @Test
    void aMemberThatIsNotANumberIsASchemaErrorNotAGap() {
        // `decimal_type.members` is `set<value>`, so nothing before the family itself refuses "abc" -- and
        // the verdict on it does not change when this library improves, so it must not read as NOT_IMPLEMENTED.
        List<Diagnostic> refused = tson().validateSchema(schemaDeclaring("!number ^ { members: [1 \"abc\"] }"));
        assertEquals(1, refused.size(), refused.toString());
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, refused.getFirst().code());
    }

    @Test
    void twoSpellingsOfOneMemberAreADuplicateInEitherFamily() {
        // §4.3's identity again, at the other end: [80 0x50] is one member written twice, which the set the
        // facet is declared as refuses. The integer family gets it from its element type, the decimal family
        // from `DecimalType` reading each member before the set is formed.
        assertEquals(Diagnostic.Code.SCHEMA_ERROR,
                tson().validateSchema(schemaDeclaring("!integer ^ { members: [80 0x50] }")).getFirst().code());
        assertEquals(Diagnostic.Code.SCHEMA_ERROR,
                tson().validateSchema(schemaDeclaring("!number ^ { members: [1 1.0] }")).getFirst().code());
    }

    private static String schemaDeclaring(String body) {
        return """
                !!id:"https://example.test/one-1.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                { t => %s }
                """.formatted(body);
    }
}
