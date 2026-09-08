package io.ltr8.tson.atom;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The indices over {@link AtomType}, keyed on the host class a value is wanted as.
 *
 * <p>What is asserted is the property a table cannot state about itself: that a primitive and its box reach
 * the same family. A component declared {@code Integer} and one declared {@code int} are one position as
 * far as a document is concerned, and a hole in one half of the pair is invisible until a caller happens to
 * declare the other.
 */
class HostAtomsTest {

    /** The eight numeric pairs, primitive against box. */
    private static final Map<Class<?>, Class<?>> BOXED = Map.of(
            byte.class, Byte.class, short.class, Short.class, int.class, Integer.class,
            long.class, Long.class, float.class, Float.class, double.class, Double.class);

    @Test
    void a_primitive_and_its_box_reach_one_family() {
        BOXED.forEach((primitive, box) -> assertEquals(
                HostAtoms.forNumberContentHostType(primitive).orElseThrow(),
                HostAtoms.forNumberContentHostType(box).orElseThrow(),
                () -> primitive + " and " + box + " must read through the same atom"));
    }

    @Test
    void the_arbitrary_precision_pair_is_bounded_by_nothing() {
        // `integer` and `number` -- the two families whose value set a Java type does not bound. Asserted
        // by reading a value no fixed width holds rather than by naming the parser, which would restate
        // the table this test exists to check.
        String past64Bits = "170141183460469231731687303715884105728";

        assertEquals(new BigInteger(past64Bits),
                HostAtoms.forNumberContentHostType(BigInteger.class).orElseThrow().read(past64Bits));
        // §5.3: digits and scale are preserved, so this is 199.90 and not 199.9.
        assertEquals("199.90",
                ((BigDecimal) HostAtoms.forNumberContentHostType(BigDecimal.class).orElseThrow().read("199.90"))
                        .toPlainString());
    }

    @Test
    void a_width_maps_to_the_built_in_of_that_width() {
        // The inverse of IntegerParser's own "narrowest Java type that holds this atom", stated as the
        // built-in names rather than as parser instances, so the claim is about core.tn and not about a
        // constructor call.
        assertEquals(BuiltinTypeVocabulary.lookup("int8").orElseThrow(),
                HostAtoms.forNumberContentHostType(byte.class).orElseThrow());
        assertEquals(BuiltinTypeVocabulary.lookup("int16").orElseThrow(),
                HostAtoms.forNumberContentHostType(short.class).orElseThrow());
        assertEquals(BuiltinTypeVocabulary.lookup("int32").orElseThrow(),
                HostAtoms.forNumberContentHostType(int.class).orElseThrow());
        assertEquals(BuiltinTypeVocabulary.lookup("int64").orElseThrow(),
                HostAtoms.forNumberContentHostType(long.class).orElseThrow());
        assertEquals(BuiltinTypeVocabulary.lookup("float32").orElseThrow(),
                HostAtoms.forNumberContentHostType(float.class).orElseThrow());
        assertEquals(BuiltinTypeVocabulary.lookup("float64").orElseThrow(),
                HostAtoms.forNumberContentHostType(double.class).orElseThrow());
    }

    /**
     * The two indices answer different questions and must not overlap: a family reading from a JSON number
     * has no business being reachable from a string, which is what would let {@code "123"} become a
     * {@code BigInteger} because a field is declared one ([TSON-DATA] §4.4, [TSON-JSON] §5.3).
     */
    @Test
    void no_host_type_is_in_both_indices() {
        List<Class<?>> numeric = List.of(byte.class, Byte.class, short.class, Short.class, int.class,
                Integer.class, long.class, Long.class, BigInteger.class, float.class, Float.class,
                double.class, Double.class, BigDecimal.class);

        for (Class<?> type : numeric) {
            assertTrue(HostAtoms.forStringContentHostType(type).isEmpty(),
                    () -> type + " reads from a number, so it must not be reachable from a string");
        }
    }
}
