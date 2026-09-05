package io.ltr8.tson.schema.meta;

import io.ltr8.tson.schema.TsonSchemaValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code decimal_type.members} is typed {@code set<value>} -- §7.4's constraint-fields rule and the bootstrap
 * ordering behind it leave the family unable to name its own atom -- so an element arrives as whatever
 * [TSON-DATA] §4 resolved it to, and nothing between the wire and the record narrows a collection's elements
 * the way a record field's scalar is narrowed. {@link DecimalType} reads each one as a decimal before the set
 * is formed, which is what meta.tn's own {@code @doc} says the resolver does, and what lets the three rules
 * that ask about a member -- the read, the tightening, the coherence check -- share one identity.
 *
 * <p>The lists here are built the way the binder builds them, with the element types a real read produces,
 * which is the whole point: a {@code List<BigDecimal>} whose first element is a {@code BigInteger} compiles
 * and reads back wrong, and only a test that reproduces the pollution can pin the fix.
 */
class DecimalMemberSetTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DecimalType withMembers(Object... written) {
        return new DecimalType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of((List<BigDecimal>) (List) List.of(written)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DecimalType boundedWithMembers(String min, String max, Object... written) {
        return new DecimalType(Optional.of(new BigDecimal(min)), Optional.empty(), Optional.of(new BigDecimal(max)),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of((List<BigDecimal>) (List) List.of(written)));
    }

    @Test
    void anIntegerFormMemberIsReadAsADecimal() {
        // `!number ^ { members: [1 2.50] }` -- §4 resolves 1 to an integer and 2.50 to a float, so the two
        // arrive as different host types and only this record knows both are decimals.
        List<BigDecimal> members = withMembers(BigInteger.ONE, new BigDecimal("2.50")).members().orElseThrow();

        assertEquals(List.of(new BigDecimal("1"), new BigDecimal("2.50")), members);
        assertTrue(members.stream().allMatch(BigDecimal.class::isInstance));
    }

    @Test
    void aMemberThatIsNotANumberIsTheAuthorsError() {
        // meta.tn: "a member that does not parse as a decimal fails at schema load". `value` admitting
        // anything is a property of the slot, not a licence for what stands in it.
        TsonSchemaValidationException refused = assertThrows(TsonSchemaValidationException.class,
                () -> withMembers(BigInteger.ONE, "abc"));
        assertTrue(refused.getMessage().contains("'abc'"), refused.getMessage());
    }

    @Test
    void twoSpellingsOfOneValueAreOneMemberAndSoADuplicate() {
        // meta.tn: "1 and 1.0 are one member and a duplicate rather than two". The `set` the facet is
        // declared as cannot see it -- its uniqueness rule runs on the decoded elements, where the two are a
        // BigInteger and a BigDecimal and nothing compares them.
        TsonSchemaValidationException refused = assertThrows(TsonSchemaValidationException.class,
                () -> withMembers(BigInteger.ONE, new BigDecimal("1.0")));
        assertTrue(refused.getMessage().contains("unique elements"), refused.getMessage());

        assertThrows(TsonSchemaValidationException.class,
                () -> withMembers(new BigDecimal("2.5"), new BigDecimal("2.50")));
    }

    @Test
    void aBoundedBodyComparesItsMembersAgainstTheBound() {
        // The comparison AtomCoherence runs is BigDecimal's own, so an unread member reached it as a
        // BigInteger and threw ClassCastException out of a schema that is merely ordinary.
        assertEquals(List.of(), boundedWithMembers("1", "10", BigInteger.ONE, new BigDecimal("2.50"))
                .coherenceCheck());
        assertTrue(boundedWithMembers("1", "10", BigInteger.valueOf(80)).coherenceCheck().stream()
                .anyMatch(v -> v.contains("member 80 is above")));
    }

    @Test
    void tighteningComparesAMemberSetByValueNotByScale() {
        // §5.7's member-set kind, under §4.3's identity: 2.5 restates 2.50 rather than adding to the set.
        DecimalType source = withMembers(new BigDecimal("1"), new BigDecimal("2.50"));

        assertEquals(List.of(), source.constraintsCheck(withMembers(new BigDecimal("2.5"))));
        assertTrue(source.constraintsCheck(withMembers(new BigDecimal("3"))).stream()
                .anyMatch(v -> v.contains("members adds")));
    }
}
