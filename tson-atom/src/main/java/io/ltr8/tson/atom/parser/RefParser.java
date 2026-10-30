package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.atom.IdentifierGrammar;
import io.ltr8.tson.schema.meta.ReferenceBody;
import java.util.Optional;

/**
 * Parses a reference that names values -- an instance of the meta-kernel's {@code reference} whose {@code of} is no
 * constructor, spelled {@code ref<method>} or {@code kind<method>} alike. A value is a qualified name: segments
 * between dots, each a [TSON-DATA] §7.7 identifier read as the kernel's {@code type_name} reads it, none empty. What
 * the name reaches is judged where it resolves, when the schema links, never here, so this parser reads schema
 * documents alone: a data document is never linked.
 *
 * <p>Not part of Part 1's published built-in vocabulary (§5), like {@link KindParser}.
 */
public record RefParser(ReferenceBody reference) implements AtomTypeParser<String> {

    @Override
    public String read(String text) {
        for (String segment : text.split("\\.", -1)) {
            Optional<String> violation = segment.isEmpty() ? Optional.of("a segment is empty")
                    : IdentifierGrammar.validate(segment);
            if (violation.isPresent()) {
                throw new AtomValidationException("'" + text + "' is no qualified name -- each segment between the "
                        + "dots is an identifier, and " + (segment.isEmpty() ? "one is empty"
                        : "'" + segment + "' is not: " + violation.get()), "a qualified name");
            }
        }
        return text;
    }

    @Override
    public String write(String value) {
        return value;
    }

    /** A reference reads to its name, so a string target is its own value and nothing else is. */
    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        return natural(String.class, target);
    }
}
