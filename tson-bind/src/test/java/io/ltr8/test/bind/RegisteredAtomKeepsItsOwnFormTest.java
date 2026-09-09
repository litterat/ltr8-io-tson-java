package io.ltr8.test.bind;

import io.ltr8.annotation.Atom;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassAtom;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registering a class as an atom finds the atom form it declares, rather than replacing it with one that
 * declares nothing.
 *
 * <p><b>What makes an atom an atom is a bridge to a type the wire can carry</b>, and every route that
 * produces one through analysis states it: {@code @Atom} on a constructor or static factory, and a plain
 * enum's own {@code name()} crossing. A bare {@code DataClassAtom} states neither -- it asserts "one scalar"
 * and supplies nothing that makes it one -- so it is usable only for a class the <em>encoding</em> already
 * knows, which is the JDK scalars and the built-in vocabulary's host types.
 *
 * <p>So {@code Builder.registerAtom(Class)} asks {@code DefaultClassBinder.atomForm} first and keeps the
 * bare form as the fallback. Without that the explicit registration was strictly worse than none: it writes
 * into the descriptor cache, so it short-circuits analysis for that class permanently, and a class that
 * would have bound through its own bridge became unreadable and unwritable instead.
 */
class RegisteredAtomKeepsItsOwnFormTest {

    @Atom
    public static final class Celsius {
        private final int degrees;

        @Atom
        public Celsius(int degrees) {
            this.degrees = degrees;
        }

        @Atom
        public int toData() {
            return degrees;
        }
    }

    @Atom
    public static final class Sku {
        private final String code;

        @Atom
        public Sku(String code) {
            this.code = code;
        }

        @Atom
        public String toData() {
            return code;
        }
    }

    @Atom
    public static final class Amount {
        private final BigDecimal value;

        @Atom
        public Amount(BigDecimal value) {
            this.value = value;
        }

        @Atom
        public BigDecimal toData() {
            return value;
        }
    }

    @Atom
    public static final class Serial {
        private final BigInteger n;

        @Atom
        public Serial(BigInteger n) {
            this.n = n;
        }

        @Atom
        public BigInteger toData() {
            return n;
        }
    }

    @Atom
    public static final class NoAccessor {
        @Atom
        public NoAccessor(int n) {
        }
    }

    public enum Colour { RED, GREEN }

    private static DataClass registered(Class<?> atom) throws DataBindException {
        return DataBindContext.builder().registerAtom(atom).build().getDescriptor(atom);
    }

    @Test
    void anAtomAnnotatedClassKeepsTheBridgeItDeclares() throws DataBindException {
        DataClass descriptor = registered(Celsius.class);

        assertInstanceOf(DataClassAtom.class, descriptor);
        assertTrue(descriptor.bridge().isPresent(), "the @Atom constructor states a crossing to int");
        assertEquals(int.class, descriptor.dataClass());
    }

    /** The registration must not lose what doing nothing would have found -- that is the whole invariant. */
    @Test
    void registeringIsNeverWorseThanNotRegistering() throws DataBindException {
        DataClass unregistered = DataBindContext.builder().build().getDescriptor(Celsius.class);

        assertEquals(unregistered.dataClass(), registered(Celsius.class).dataClass());
    }

    @Test
    void anEnumKeepsItsOwnNameCrossing() throws DataBindException {
        DataClass descriptor = registered(Colour.class);

        assertTrue(descriptor.bridge().isPresent(), "an enum crosses to its name()");
        assertEquals(String.class, descriptor.dataClass());
    }

    /**
     * A class the encoding already knows as a scalar has no form of its own to find, and the bare
     * {@code DataClassAtom} is the right answer for it -- {@code AtomContext.hostTypes()} registers a dozen
     * such classes this way.
     */
    @Test
    void aClassDeclaringNoAtomFormStillRegistersBare() throws DataBindException {
        DataClass descriptor = registered(UUID.class);

        assertInstanceOf(DataClassAtom.class, descriptor);
        assertTrue(descriptor.bridge().isEmpty());
        assertEquals(UUID.class, descriptor.dataClass());
    }

    /**
     * Every type the wire carries directly is admitted as an {@code @Atom} class's one component:
     * primitives answer for themselves, and the boxes, {@code String} and the two arbitrary-precision
     * numbers are the set. The last three are what a code, a money amount and a large identifier need, and
     * refusing them made {@code @Atom} the one route to an atom that could not describe them.
     */
    @Test
    void everyTypeTheWireCarriesIsAdmittedAsTheComponent() throws DataBindException {
        assertEquals(String.class, registered(Sku.class).dataClass());
        assertEquals(BigDecimal.class, registered(Amount.class).dataClass());
        assertEquals(BigInteger.class, registered(Serial.class).dataClass());
    }

    /**
     * And a class that declares an atom form <em>badly</em> fails out of registration rather than falling
     * back: the author said what they wanted, and the bare form is not it.
     */
    @Test
    void aBadlyDeclaredAtomFormIsRefusedRatherThanFallenBackFrom() {
        assertThrows(IllegalArgumentException.class,
                () -> DataBindContext.builder().registerAtom(NoAccessor.class).build());
    }
}
