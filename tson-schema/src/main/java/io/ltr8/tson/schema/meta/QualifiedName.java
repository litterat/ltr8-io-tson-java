package io.ltr8.tson.schema.meta;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A value written by name: segments joined by {@code .}, each an identifier, as a slot holding a value declared
 * elsewhere spells it (SPEC-FEEDBACK.md #13). {@code orders.methods.place_order} names the entry {@code orders},
 * the field {@code methods} of its body, and the key {@code place_order} of that field's map.
 *
 * <p><b>Recorded as written.</b> The segments are the author's, unresolved: the first is a type name, resolved
 * as a {@link TypeRef}'s is, locally and through imports with alias hops followed ([TSON-SCHEMA] §8.3); each
 * further one selects in the value reached, a field of a record and then a key of a map. The linker does that
 * walk and judges what it reaches; resolved output writes the name back as it was written, as a reference chain
 * is. {@link TypeRef} is the one-segment case that carries arguments, and stays its own record.
 *
 * <p><b>Structural here, grammatical where it is read.</b> This record holds only what makes the value a name
 * of segments -- at least one, none empty, none containing the separator. Whether each segment is an
 * identifier ([TSON-DATA] §7.7's profile, of which {@code .} is not part) is decided by the reader that turns
 * a token into one, which holds the identifier grammar; this module does not.
 */
public record QualifiedName(List<String> segments) {

    /** The segment separator, which [TSON-DATA] §7.1 reserves and the identifier profile excludes. */
    public static final char SEPARATOR = '.';

    public QualifiedName {
        segments = List.copyOf(segments);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("a qualified name has at least one segment");
        }
        for (String segment : segments) {
            if (segment.isEmpty() || segment.indexOf(SEPARATOR) >= 0) {
                throw new IllegalArgumentException("'" + segment + "' is not one segment of a qualified name: a "
                        + "segment is non-empty and holds no '" + SEPARATOR + "'");
            }
        }
    }

    /** A name of the segments given, in order. */
    public static QualifiedName of(String... segments) {
        return new QualifiedName(List.of(segments));
    }

    /**
     * {@code text} split at each {@code .}, or empty where that leaves an empty segment ({@code a..b},
     * {@code .a}, {@code a.}). The identifier grammar is the caller's to apply to each segment.
     */
    public static Optional<QualifiedName> split(String text) {
        Objects.requireNonNull(text, "text");
        List<String> segments = List.of(text.split("\\.", -1));
        for (String segment : segments) {
            if (segment.isEmpty()) {
                return Optional.empty();
            }
        }
        return Optional.of(new QualifiedName(segments));
    }

    /** The first segment: the entry the name starts from. */
    public String head() {
        return segments.getFirst();
    }

    /** The name as written: the segments joined by {@code .}. */
    @Override
    public String toString() {
        return String.join(String.valueOf(SEPARATOR), segments);
    }
}
