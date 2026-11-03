package io.ltr8.tson.schema.numeric;

import io.ltr8.tson.base.SchemaValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code decimal_type.members} is a set of {@code number}, so every member arrives as the {@link BigDecimal}
 * that reader produced, kept as written. {@link DecimalType} forms the set under [TSON-DATA] §4.3's identity,
 * the value denoted, which is what lets the three rules that ask about a member -- the read, the tightening,
 * the coherence check -- share one identity, and what makes {@code 1} and {@code 1.0} one member.
 */
class DecimalMemberSetTest {

    private static DecimalType withMembers(String... written) {
        return new DecimalType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(decimals(written)));
    }

    private static DecimalType boundedWithMembers(String min, String max, String... written) {
        return new DecimalType(Optional.of(new BigDecimal(min)), Optional.empty(), Optional.of(new BigDecimal(max)),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(decimals(written)));
    }

    private static List<BigDecimal> decimals(String... written) {
        return Arrays.stream(written).map(BigDecimal::new).toList();
    }

    @Test
    void aMemberIsKeptAsWritten() {
        assertEquals(List.of(new BigDecimal("1"), new BigDecimal("2.50")),
                withMembers("1", "2.50").members().orElseThrow());
    }

    @Test
    void twoSpellingsOfOneValueAreOneMemberAndSoADuplicate() {
        // numeric.tn: "1 and 1.0 are one member and a duplicate rather than two". BigDecimal's own equals
        // carries the scale, so the set is formed under compareTo.
        SchemaValidationException refused = assertThrows(SchemaValidationException.class,
                () -> withMembers("1", "1.0"));
        assertTrue(refused.getMessage().contains("unique elements"), refused.getMessage());

        assertThrows(SchemaValidationException.class, () -> withMembers("2.5", "2.50"));
    }

    @Test
    void aBoundedBodyComparesItsMembersAgainstTheBound() {
        assertEquals(List.of(), boundedWithMembers("1", "10", "1", "2.50").coherenceCheck());
        assertTrue(boundedWithMembers("1", "10", "80").coherenceCheck().stream()
                .anyMatch(v -> v.contains("member 80 is above")));
    }

    @Test
    void tighteningComparesAMemberSetByValueNotByScale() {
        // §5.7's member-set kind, under §4.3's identity: 2.5 restates 2.50 rather than adding to the set.
        DecimalType source = withMembers("1", "2.50");

        assertEquals(List.of(), source.constraintsCheck(withMembers("2.5")));
        assertTrue(source.constraintsCheck(withMembers("3")).stream()
                .anyMatch(v -> v.contains("members adds")));
    }
}
