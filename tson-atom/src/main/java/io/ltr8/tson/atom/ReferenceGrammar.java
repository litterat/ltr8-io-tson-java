package io.ltr8.tson.atom;

import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.unicode.Normalization;

import java.util.List;
import java.util.Optional;

/**
 * A reference's text, whatever the token's form ([TSON-DATA] §2.4): split at each {@code .} into segments before any
 * family is consulted, none empty, the first a [TSON-DATA] §7.7 identifier, it naming an entry of the root
 * namespace ([TSON-SCHEMA] §5.12). A type's name enters schema maps alone, so every segment of one is an identifier
 * ({@link #parse}); a further segment of a value's name is judged by the family of what it enters, which only the
 * walk that resolves it knows ({@link #valueName}). The name is the text in NFC, as a quoted name is read at every
 * naming position ([TSON-DATA] §7.7), so a decomposed spelling is the name its composed one is.
 *
 * <p><b>A reference is a name</b> ([TSON-SCHEMA] §4.2). An argument list is schema grammar: in text the stream
 * refuses one as a parse error before any position reads it ([TSON-DATA] §7.2.5), and text holding one here fails
 * this grammar as any other text that is no name does, the message saying how an application is written.
 *
 * <p>The grammar alone: what the name resolves to is the position's to judge.
 */
public final class ReferenceGrammar {

    private ReferenceGrammar() {
    }

    /**
     * {@code text} as the name of a type it spells, every segment an identifier.
     *
     * @throws AtomParseException where it spells no type's name, naming what failed
     */
    public static TypeRef parse(String text) {
        String name = Normalization.NFC.apply(text);
        malformed(name).or(() -> segmentViolation(segmentsOf(name))).ifPresent(detail -> {
            throw error(name, detail);
        });
        return TypeRef.of(name);
    }

    /**
     * {@code text} as the qualified name of a value it spells, its segments past the first unjudged.
     *
     * @throws AtomParseException where {@link #malformed} refuses it
     */
    public static TypeRef valueName(String text) {
        String name = Normalization.NFC.apply(text);
        malformed(name).ifPresent(detail -> {
            throw error(name, detail);
        });
        return TypeRef.of(name);
    }

    /**
     * Why {@code text} splits into no qualified name -- it is empty, carries an argument list, has an empty segment,
     * or its first segment is no identifier -- or empty where it splits.
     */
    public static Optional<String> malformed(String text) {
        if (text.isEmpty()) {
            return Optional.of("a reference names something, and this is empty");
        }
        int angle = text.indexOf('<');
        if (angle > 0 && isQualifiedName(text.substring(0, angle))) {
            String head = text.substring(0, angle);
            return Optional.of("an argument list is schema grammar, and a reference is a name ([TSON-SCHEMA] "
                    + "§4.2): name the application by the declaration that gives it one ('my_" + head + " => "
                    + head + "<...>', then 'my_" + head + "'), or at a schema's type slot write it in place, "
                    + "'{ of: !meta.apply { head: " + head + "  arguments: [...] } }'");
        }
        List<String> segments = segmentsOf(text);
        if (segments.contains("")) {
            return Optional.of("'" + text + "' has an empty segment -- each segment between the dots is a name");
        }
        return segmentViolation(segments.subList(0, 1));
    }

    /** Whether {@code text} is a qualified name whose every segment is an identifier, one unquoted token. */
    public static boolean isQualifiedName(String text) {
        if (text.isEmpty()) {
            return false;
        }
        List<String> segments = segmentsOf(text);
        return !segments.contains("") && segmentViolation(segments).isEmpty();
    }

    private static List<String> segmentsOf(String text) {
        return List.of(text.split("\\.", -1));
    }

    private static Optional<String> segmentViolation(List<String> segments) {
        for (String segment : segments) {
            Optional<String> violation = IdentifierGrammar.validate(segment);
            if (violation.isPresent()) {
                return Optional.of("'" + segment + "' is no name: " + violation.get());
            }
        }
        return Optional.empty();
    }

    private static AtomParseException error(String text, String detail) {
        return new AtomParseException("'" + text + "' is no reference -- " + detail, "a reference");
    }
}
