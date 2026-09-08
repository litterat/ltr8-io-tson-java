package io.ltr8.tson.atom;

import io.ltr8.tson.atom.parser.BytesParser;
import io.ltr8.tson.atom.parser.ComplexParser;
import io.ltr8.tson.atom.parser.DateParser;
import io.ltr8.tson.atom.parser.DateTimeParser;
import io.ltr8.tson.atom.parser.DecimalParser;
import io.ltr8.tson.atom.parser.DurationParser;
import io.ltr8.tson.atom.parser.FloatParser;
import io.ltr8.tson.atom.parser.IntegerParser;
import io.ltr8.tson.atom.parser.Ipv4Parser;
import io.ltr8.tson.atom.parser.Ipv6Parser;
import io.ltr8.tson.atom.parser.PeriodParser;
import io.ltr8.tson.atom.parser.RationalParser;
import io.ltr8.tson.atom.parser.TimeParser;
import io.ltr8.tson.atom.parser.UriParser;
import io.ltr8.tson.atom.parser.UuidParser;
import io.ltr8.tson.base.atom.Complex;
import io.ltr8.tson.base.atom.Rational;
import io.ltr8.tson.schema.meta.IntegerSize;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which built-in atom reads a given host class -- the reverse of {@link BuiltinTypeVocabulary}'s
 * name-&gt;{@link AtomType} table, keyed on the Java type a value arrives as rather than the name a schema
 * wrote.
 *
 * <p><b>It exists for the {@code value}-typed slot.</b> [TSON-SCHEMA] §7.4 types a constructor's constraint
 * fields {@code value}, and the bootstrap ordering behind it leaves no choice: {@code duration_type} is what
 * defines a duration, and {@code duration => !duration_type {}} lives a layer up in core.tn, so meta.tn
 * cannot write {@code min: duration}. A slot typed {@code value} is decoded by [TSON-DATA] §4 base type
 * resolution and by nothing else, which resolves three classes and none of them is a duration -- so {@code
 * min: PT30M} arrives as the string {@code PT30M} and the position's own type is the only thing that says
 * what it meant. This table is how {@code TokenValue, Class)}
 * asks.
 *
 * <p>The entries are unconstrained instances, deliberately: a bound is a value of the atom's own type, not
 * of the refinement being declared, and asking a half-built refinement to validate its own bound would be
 * circular ({@code min} of an atom whose {@code min} is what is being read).
 *
 * <p><b>Not the same table as the writers' own</b> ({@code VocabularyAtoms}), and the difference is the
 * numeric families. A writer must not annotate every {@code BigDecimal} it emits with {@code !number}, so it
 * carries no reverse entry for one; a reader asking "what should this slot's token have been" must answer
 * for {@code number} too, or {@code !number ^ { min: "abc" }} has nothing to be refused by. The two tables
 * answer different questions and only look alike.
 */
public final class HostAtoms {

    private HostAtoms() {
    }

    private static final Map<Class<?>, AtomType<?>> BY_HOST_TYPE = Map.ofEntries(
            Map.entry(BigDecimal.class, DecimalParser.UNCONSTRAINED),
            Map.entry(BigInteger.class, IntegerParser.UNCONSTRAINED),
            Map.entry(LocalDate.class, DateParser.UNCONSTRAINED),
            Map.entry(OffsetTime.class, TimeParser.UNCONSTRAINED),
            Map.entry(OffsetDateTime.class, DateTimeParser.UNCONSTRAINED),
            Map.entry(Duration.class, DurationParser.UNCONSTRAINED),
            Map.entry(Period.class, PeriodParser.UNCONSTRAINED),
            Map.entry(Rational.class, RationalParser.UNCONSTRAINED),
            Map.entry(Complex.class, ComplexParser.UNCONSTRAINED),
            Map.entry(UUID.class, UuidParser.UNCONSTRAINED),
            Map.entry(URI.class, UriParser.UNCONSTRAINED),
            Map.entry(byte[].class, BytesParser.BASE64),
            Map.entry(Inet4Address.class, Ipv4Parser.UNCONSTRAINED),
            Map.entry(Inet6Address.class, Ipv6Parser.UNCONSTRAINED));

    /**
     * The host classes of [TSON-JSON] §5.6's <b>string-content</b> families -- the ones whose wire form is a
     * quoted string, so a reader with no type-ref to dispatch on may let the target class say what the
     * string content means. The numeric families are deliberately absent: [TSON-DATA] §4.4 makes a quoted
     * token a string, and §5.3/§5.4 read exact and approximate numerics from a number rather than a string,
     * so letting {@code "123"} become a {@code BigInteger} because a field is declared one would overrule
     * base type resolution with a class.
     */
    private static final Map<Class<?>, AtomType<?>> BY_STRING_CONTENT_HOST_TYPE = Map.ofEntries(
            Map.entry(LocalDate.class, DateParser.UNCONSTRAINED),
            Map.entry(OffsetTime.class, TimeParser.UNCONSTRAINED),
            Map.entry(OffsetDateTime.class, DateTimeParser.UNCONSTRAINED),
            Map.entry(Duration.class, DurationParser.UNCONSTRAINED),
            Map.entry(Period.class, PeriodParser.UNCONSTRAINED),
            Map.entry(UUID.class, UuidParser.UNCONSTRAINED),
            Map.entry(URI.class, UriParser.UNCONSTRAINED),
            Map.entry(byte[].class, BytesParser.BASE64),
            Map.entry(Inet4Address.class, Ipv4Parser.UNCONSTRAINED),
            Map.entry(Inet6Address.class, Ipv6Parser.UNCONSTRAINED));

    /**
     * The host classes of the <b>numeric</b> families -- the exact tier ([TSON-JSON] §5.3) and the
     * approximate one (§5.4) -- keyed on the Java type a value is wanted as, primitives beside their boxes.
     *
     * <p><b>The inverse of {@code IntegerParser.hostType}</b>, which already answers the forward direction:
     * given an atom, the narrowest Java type that holds it. Stating the reverse is what lets a reader with
     * no type-ref read a number by the contract of the family its target names rather than by narrowing a
     * host value the encoding chose -- so {@code 1.0} at an {@code int} is {@code int32}'s contract
     * rejecting a float form, which §5.3 requires, and not a {@code BigDecimal} being asked whether it
     * happens to be integral.
     *
     * <p><b>Every primitive is keyed beside its box</b>, and that is the entry easiest to leave out: a
     * component declared {@code Integer} and one declared {@code int} are one position as far as a document
     * is concerned, so a hole in one half of a pair is invisible until a caller happens to declare the
     * other. {@code HostAtomsTest} pins the pairing rather than the table. {@link Number} itself is
     * deliberately absent -- it is abstract and names no family, so there is nothing to answer with, and
     * {@code tson-bind} refuses such a component a layer earlier in any case.
     *
     * <p><b>It is an interpretation, and this is the one worth committing to.</b> Nothing says a Java
     * {@code int} means {@code int32} rather than an {@code integer} bounded to 32 bits; the two admit the
     * same values, and the first is what makes a schemaless read a preview of the schema-directed one -- an
     * {@code int} component reaches the reader the same {@code int32} field would.
     */
    private static final Map<Class<?>, AtomType<?>> BY_NUMBER_CONTENT_HOST_TYPE = Map.ofEntries(
            Map.entry(byte.class, integer(8)), Map.entry(Byte.class, integer(8)),
            Map.entry(short.class, integer(16)), Map.entry(Short.class, integer(16)),
            Map.entry(int.class, integer(32)), Map.entry(Integer.class, integer(32)),
            Map.entry(long.class, integer(64)), Map.entry(Long.class, integer(64)),
            Map.entry(BigInteger.class, IntegerParser.UNCONSTRAINED),
            Map.entry(float.class, FloatParser.FLOAT32), Map.entry(Float.class, FloatParser.FLOAT32),
            Map.entry(double.class, FloatParser.FLOAT64), Map.entry(Double.class, FloatParser.FLOAT64),
            Map.entry(BigDecimal.class, DecimalParser.UNCONSTRAINED));

    /** {@code core.tn}'s own {@code int<bits> => !integer ^ { size: { bits: <bits> signed: true } } }. */
    private static AtomType<?> integer(int bits) {
        return new IntegerParser(new IntegerSize(bits, true));
    }

    /**
     * The built-in atom that reads {@code hostType} from a <b>number</b>, or empty where no numeric family
     * produces that class.
     *
     * <p>What an encoding whose kinds already do the classifying asks -- [TSON-JSON] §4.1 makes the
     * position's type decide and §5.7 says outright that its decode "needs none of base type resolution".
     * <b>The text encoding does not ask</b>, and must not: [TSON-DATA] §4 makes base type resolution
     * normative for an untyped token there, so the token is classified first and the target is a narrowing
     * question afterwards ({@code AtomBinder}). Both readings are right for their own encoding, which is why
     * this is a separate index rather than a widening of {@link #forStringContentHostType}.
     */
    public static Optional<AtomType<?>> forNumberContentHostType(Class<?> hostType) {
        return Optional.ofNullable(BY_NUMBER_CONTENT_HOST_TYPE.get(hostType));
    }

    /** The built-in atom whose values are {@code hostType}, or empty where no built-in produces that class. */
    public static Optional<AtomType<?>> forHostType(Class<?> hostType) {
        return Optional.ofNullable(BY_HOST_TYPE.get(hostType));
    }

    /**
     * The built-in atom whose values are {@code hostType} <b>and whose wire form is a string</b> (§5.6), or
     * empty. What a reader dispatching on the target class rather than on a type-ref asks -- a schemaless
     * TSON read of a quoted token, and every JSON read, where §4.1 makes the position's type decide and
     * there is no type-ref to be had.
     */
    public static Optional<AtomType<?>> forStringContentHostType(Class<?> hostType) {
        return Optional.ofNullable(BY_STRING_CONTENT_HOST_TYPE.get(hostType));
    }
}
