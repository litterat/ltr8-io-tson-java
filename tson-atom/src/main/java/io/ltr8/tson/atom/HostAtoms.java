package io.ltr8.tson.atom;

import io.ltr8.tson.atom.parser.BytesParser;
import io.ltr8.tson.atom.parser.ComplexParser;
import io.ltr8.tson.atom.parser.DateParser;
import io.ltr8.tson.atom.parser.DateTimeParser;
import io.ltr8.tson.atom.parser.DecimalParser;
import io.ltr8.tson.atom.parser.DurationParser;
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
