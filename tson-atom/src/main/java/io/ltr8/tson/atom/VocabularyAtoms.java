package io.ltr8.tson.atom;

import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.parser.BytesParser;
import io.ltr8.tson.atom.parser.ComplexParser;
import io.ltr8.tson.atom.parser.DateParser;
import io.ltr8.tson.atom.parser.DateTimeParser;
import io.ltr8.tson.atom.parser.DurationParser;
import io.ltr8.tson.atom.parser.Ipv4Parser;
import io.ltr8.tson.atom.parser.Ipv6Parser;
import io.ltr8.tson.atom.parser.PeriodParser;
import io.ltr8.tson.atom.parser.RationalParser;
import io.ltr8.tson.atom.parser.TimeParser;
import io.ltr8.tson.atom.parser.UriParser;
import io.ltr8.tson.atom.parser.UuidParser;
import io.ltr8.tson.atom.parser.*;
import io.ltr8.tson.base.atom.Complex;
import io.ltr8.tson.base.atom.Rational;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The reverse of {@link BuiltinTypeVocabulary}'s name-&gt;{@link AtomType} table: which {@code AtomType}
 * (and under what type-ref name) writes a given JDK-backed host class's values. Shared by the two writers
 * that turn a value into TSON text -- {@code TsonObjectWriter} (a Java object graph) and
 * {@code TsonTreeWriter} (a {@code TsonValue} tree).
 *
 * <p>Curated by hand, not derived from {@code BuiltinTypeVocabulary} wholesale: only the vocabulary's
 * JDK-object host types appear. The integer/decimal/float family has no unique reverse mapping at all
 * (many names, e.g. {@code int8}..{@code int256}, bind to one host type, so a bound {@code long} carries no
 * way to know which produced it), so those are written bare on the default-atom path instead -- see
 * {@code TsonObjectWriter.toTson}.
 *
 * <p><b>{@code text} is deliberately absent, though it is a §5.5 built-in like {@code uuid}.</b> Its host
 * class is {@code String}, which is what a writer emits bare, so a reverse entry would annotate every
 * string in every document with {@code !text} -- and §5.5's own point is that the annotation exists to
 * <em>assert</em> the string case where it is in doubt, not to restate it everywhere. The same reasoning
 * keeps the numeric families out, one step further along.
 */
public final class VocabularyAtoms {

    /** Pairs an {@link AtomType} with the type-ref name a writer emits its values under. */
    public record Entry(String typeRef, AtomType<?> atomType) {
        @SuppressWarnings("unchecked")
        public String write(Object value) {
            return ((AtomType<Object>) atomType).write(value);
        }
    }

    private VocabularyAtoms() {
    }

    /**
     * A fresh map per writer rather than a shared static, so no writer can alter what another emits.
     *
     * <p><b>It is keyed by the <em>wire</em> class, which is why a consumer's own type never appears in
     * it.</b> A writer applies a {@code DataClass}'s bridge before dispatching, so a class registered as an
     * atom arrives here as the value it crosses to -- a {@code Money} bridged to text arrives as the
     * {@code String}, a {@code Ticket} bridged to a {@code UUID} as the {@code UUID}, which is in this table
     * and so is written {@code !uuid}. Every type an atom may bridge to is either named here or written by
     * {@code AtomWriter.writeDefaultAtom}, so there is nothing a caller could usefully add: the extension
     * point is {@code DataBindContext.Builder.registerAtom} and {@code @Atom}, one layer down, where the
     * crossing is stated once and serves both directions.
     */
    public static Map<Class<?>, Entry> defaults() {
        Map<Class<?>, Entry> atoms = new HashMap<>();
        atoms.put(UUID.class, new Entry(UuidParser.TYPENAME, UuidParser.UNCONSTRAINED));
        atoms.put(URI.class, new Entry(UriParser.TYPENAME, UriParser.UNCONSTRAINED));
        atoms.put(Inet4Address.class, new Entry(Ipv4Parser.TYPENAME, Ipv4Parser.UNCONSTRAINED));
        atoms.put(Inet6Address.class, new Entry(Ipv6Parser.TYPENAME, Ipv6Parser.UNCONSTRAINED));
        atoms.put(LocalDate.class, new Entry(DateParser.TYPENAME, DateParser.UNCONSTRAINED));
        atoms.put(OffsetTime.class, new Entry(TimeParser.TYPENAME, TimeParser.UNCONSTRAINED));
        atoms.put(OffsetDateTime.class, new Entry(DateTimeParser.TYPENAME, DateTimeParser.UNCONSTRAINED));
        // Not a default among four any more: !bytes is the only built-in name, and it is base64.
        atoms.put(byte[].class, new Entry(BytesParser.TYPENAME, BytesParser.BASE64));
        atoms.put(Rational.class, new Entry(RationalParser.TYPENAME, RationalParser.UNCONSTRAINED));
        atoms.put(Complex.class, new Entry(ComplexParser.TYPENAME, ComplexParser.UNCONSTRAINED));
        atoms.put(java.time.Duration.class, new Entry(DurationParser.TYPENAME, DurationParser.UNCONSTRAINED));
        atoms.put(java.time.Period.class, new Entry(PeriodParser.TYPENAME, PeriodParser.UNCONSTRAINED));
        return atoms;
    }
}
