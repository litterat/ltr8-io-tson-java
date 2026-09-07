package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.lexer.Nfc;
import io.ltr8.tson.tree.TsonAnnotation;
import io.ltr8.tson.tree.TsonAtom;

import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * What a decoded value compares as -- the equality contract [TSON-SCHEMA] §7.5, §5.2 and [TSON-DATA] §2.6
 * each delegate to and none of them defines.
 *
 * <p><b>Three rules in the series compare two decoded values, and they must not disagree.</b> §7.5's
 * duplicate rule ("two values are duplicates if the element type's equality contract considers them equal"),
 * §2.6's map-key identity ("the decoded host value, type-ref and annotations stripped") and §5.2's check of a
 * stated FIXED value against its declared one are three questions with one answer, so they ask it here rather
 * than each at its own call site. §4.3 writes the contract down for one family; this is where the others get
 * theirs.
 *
 * <p><b>Four host types do not compare as themselves.</b> A {@code String} compares NFC-normalised, which
 * §2.6 makes the minimum a processor must relate and which the lexer applies to unquoted tokens only -- so a
 * quoted {@code "café"} and an unquoted {@code café} are one value and must not both be admitted
 * to a set. A {@code byte[]} carries Java's identity equality, so without this no two decoded binary values
 * are ever equal: a set admits every duplicate there is, a map admits a key twice, and a FIXED field rejects
 * the one value it exists to accept.
 *
 * <p><b>The two temporal families compare as instants, because [TSON-SCHEMA] §5.5 makes the offset a
 * spelling</b>: a {@code datetime} is the instant on the UTC timeline and a {@code time} is the time of day
 * in UTC, so {@code 2026-01-01T10:00:00+01:00} and {@code 2026-01-01T09:00:00Z} are one value, {@code -00:00}
 * is the same instant as {@code Z}, and {@code 23:30:00-02:00} is {@code 01:30:00Z}. {@link
 * OffsetDateTime#equals}/{@link OffsetTime#equals} compare the offset instead, which is a narrower relation
 * than the value space, so all three rules above were wrong here at once. Ordering needs nothing: both host
 * types' {@code compareTo} already compares the instant. §5.5 has TSON text preserve the offset as written,
 * so this is an identity and never what a reader hands back.
 *
 * <p><b>A type-ref and its annotations stay part of an atom's identity, and that is deliberate.</b> §2.6
 * strips them for a *key*, where the schema fixes the key type and a tag can only restate it, and the tree
 * readers do that at the key sites. Stripping them here would reach set elements too, where a choice's
 * variants are told apart by exactly that tag: {@code !cm 5} and {@code !inch 5} are two values, and merging
 * them would report a duplicate in a document that has none. So an atom is identified by its normalised
 * value beside whatever it was tagged with.
 */
final class ValueIdentity {

    private ValueIdentity() {
    }

    /**
     * {@code decoded} as the thing it compares as -- itself, for every host type whose Java equality is
     * already its value equality.
     */
    static Object of(Object decoded) {
        return switch (decoded) {
            case String text -> Nfc.of(text);
            // ByteBuffer.wrap is a view, not a copy: it takes the same array and adds the value equality and
            // hash the array itself does not have. Nothing mutates a decoded value, so sharing is safe.
            case byte[] octets -> ByteBuffer.wrap(octets);
            case OffsetDateTime moment -> moment.toInstant();
            // The time of day in UTC, which wraps: 23:30:00-02:00 is 01:30:00Z, a day later and the same time.
            case OffsetTime clock -> clock.withOffsetSameInstant(ZoneOffset.UTC).toLocalTime();
            case TsonAtom atom -> new Atom(of(atom.value()), atom.typeRef(), atom.annotations());
            default -> decoded;
        };
    }

    /**
     * A tree atom's identity: {@link TsonAtom}'s own three components with the value replaced by what it
     * compares as. A record rather than a rebuilt {@code TsonAtom}, because the normalised value is not a
     * value the tree model should ever be able to hand back -- a {@link ByteBuffer} is an identity, not a
     * host type any reader produces.
     */
    private record Atom(Object value, Optional<String> typeRef, List<TsonAnnotation> annotations) {
    }
}
