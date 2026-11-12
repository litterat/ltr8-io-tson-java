package io.ltr8.tson.atom;

import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Optional;

/**
 * A reference's text: a qualified name ([TSON-DATA] §7.7), segments joined by {@code .}, each an identifier, read into
 * the {@link TypeRef} it spells. It is what a JSON string at a reference-typed position holds, JSON having no
 * unquoted form, and the text form {@link TypeRef#text} writes.
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
     * {@code text} as the name it spells.
     *
     * @throws AtomParseException where it spells no qualified name, naming what failed
     */
    public static TypeRef parse(String text) {
        if (text.isEmpty()) {
            throw error(text, "a reference names something, and this is empty");
        }
        int angle = text.indexOf('<');
        if (angle > 0 && isQualifiedName(text.substring(0, angle))) {
            String head = text.substring(0, angle);
            throw error(text, "an argument list is schema grammar, and a reference is a name ([TSON-SCHEMA] §4.2): "
                    + "name the application by the declaration that gives it one ('my_" + head + " => " + head
                    + "<...>', then 'my_" + head + "'), or at a schema's type slot write it in place, '{ of: "
                    + "!meta.apply { head: " + head + "  arguments: [...] } }'");
        }
        violation(text).ifPresent(detail -> {
            throw error(text, detail);
        });
        return TypeRef.of(text);
    }

    /** Whether {@code text} is a qualified name: segments joined by {@code .}, each an identifier (§7.7). */
    public static boolean isQualifiedName(String text) {
        return violation(text).isEmpty();
    }

    private static Optional<String> violation(String text) {
        for (String segment : text.split("\\.", -1)) {
            if (segment.isEmpty()) {
                return Optional.of("'" + text + "' has an empty segment -- each segment between the dots is a name");
            }
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
