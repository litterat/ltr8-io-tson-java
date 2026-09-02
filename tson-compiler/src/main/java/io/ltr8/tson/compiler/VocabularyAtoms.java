package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.atom.*;
import io.ltr8.tson.schema.atom.Complex;
import io.ltr8.tson.schema.atom.Rational;

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
 * that turn a value into TSON text -- {@link TsonObjectWriter} (a Java object graph) and {@link
 * TsonTreeWriter} (a {@code TsonValue} tree).
 *
 * <p>Curated by hand, not derived from {@code BuiltinTypeVocabulary} wholesale: only the vocabulary's
 * JDK-object host types appear. The integer/decimal/float family has no unique reverse mapping at all
 * (many names, e.g. {@code int8}..{@code int256}, bind to one host type, so a bound {@code long} carries no
 * way to know which produced it), so those are written bare on the default-atom path instead -- see {@link
 * TsonObjectWriter#toTson}.
 *
 * <p><b>{@code text} is deliberately absent, though it is a §5.5 built-in like {@code uuid}.</b> Its host
 * class is {@code String}, which is what a writer emits bare, so a reverse entry would annotate every
 * string in every document with {@code !text} -- and §5.5's own point is that the annotation exists to
 * <em>assert</em> the string case where it is in doubt, not to restate it everywhere. The same reasoning
 * keeps the numeric families out, one step further along.
 */
final class VocabularyAtoms {

    /** Pairs an {@link AtomType} with the type-ref name a writer emits its values under. */
    record Entry(String typeRef, AtomType<?> atomType) {
        @SuppressWarnings("unchecked")
        String write(Object value) {
            return ((AtomType<Object>) atomType).write(value);
        }
    }

    private VocabularyAtoms() {
    }

    /**
     * A fresh, mutable map -- deliberately per-writer, not a shared static, so a caller wanting to extend
     * the vocabulary with their own {@code AtomType} has an actual map to add to rather than a global to
     * work around (the write-side mirror of {@code DataBindContext#registerAtom}).
     */
    static Map<Class<?>, Entry> defaults() {
        Map<Class<?>, Entry> atoms = new HashMap<>();
        atoms.put(UUID.class, new Entry(UuidParser.TYPENAME, UuidParser.UNCONSTRAINED));
        atoms.put(URI.class, new Entry(UriParser.TYPENAME, UriParser.UNCONSTRAINED));
        atoms.put(Inet4Address.class, new Entry(Ipv4Parser.TYPENAME, Ipv4Parser.UNCONSTRAINED));
        atoms.put(Inet6Address.class, new Entry(Ipv6Parser.TYPENAME, Ipv6Parser.UNCONSTRAINED));
        atoms.put(LocalDate.class, new Entry(DateParser.TYPENAME, DateParser.UNCONSTRAINED));
        atoms.put(OffsetTime.class, new Entry(TimeParser.TYPENAME, TimeParser.UNCONSTRAINED));
        atoms.put(OffsetDateTime.class, new Entry(DateTimeParser.TYPENAME, DateTimeParser.UNCONSTRAINED));
        // base64 is an arbitrary but reasonable default -- which of base64/base64url/base32/hex a byte[]
        // was decoded from doesn't survive decoding (see TsonObjectWriter#toTson). A tree node that kept
        // its own type-ref overrides this default (see TsonTreeWriter).
        atoms.put(byte[].class, new Entry(BinaryParser.BASE64.typeName(), BinaryParser.BASE64));
        atoms.put(Rational.class, new Entry(RationalParser.TYPENAME, RationalParser.UNCONSTRAINED));
        atoms.put(Complex.class, new Entry(ComplexParser.TYPENAME, ComplexParser.UNCONSTRAINED));
        atoms.put(java.time.Duration.class, new Entry(DurationParser.TYPENAME, DurationParser.UNCONSTRAINED));
        atoms.put(java.time.Period.class, new Entry(PeriodParser.TYPENAME, PeriodParser.UNCONSTRAINED));
        return atoms;
    }
}
