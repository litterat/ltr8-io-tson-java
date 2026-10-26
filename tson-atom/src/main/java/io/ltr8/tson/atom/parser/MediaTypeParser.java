package io.ltr8.tson.atom.parser;

import io.ltr8.net.MediaType;
import io.ltr8.net.MediaTypeSyntaxException;
import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.schema.meta.MediaTypeType;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Parses and validates against meta.tn's {@code media_type_type} (net.tn's {@code media_type}): a media type and its
 * parameters, read to an {@link MediaType}, whose grammar and equality are {@code io.ltr8.net}'s. A token that is not
 * a media type is a parse failure; one outside a facet -- parameters where {@code allow_parameters} is false, a type
 * or suffix the lists do not hold -- is a constraint violation, the value being a media type.
 *
 * <p>A {@code String} target receives the canonical text, so a list declared {@code List<String>} compares by
 * value.
 */
public record MediaTypeParser(MediaTypeType constraints) implements AtomTypeParser<MediaType> {

    /** net.tn's annotation name -- {@code !media_type}. */
    public static final String TYPENAME = "media_type";

    /** {@code media_type => !media_type_type {}}. */
    public static final MediaTypeParser UNCONSTRAINED = new MediaTypeParser(MediaTypeType.UNCONSTRAINED);

    @Override
    public MediaType read(String text) {
        MediaType value;
        try {
            value = MediaType.parse(text);
        } catch (MediaTypeSyntaxException e) {
            throw new AtomParseException("'" + text + "' is not a media type: it " + e.reason(), "a media type");
        }
        if (!constraints.allowParameters() && !value.parameters().isEmpty()) {
            throw new AtomValidationException("'" + text + "' carries parameters, and this type admits a media "
                    + "type without them (allow_parameters: false)", "a media type without parameters");
        }
        List<String> types = constraints.types().orElse(null);
        if (types != null && types.stream().noneMatch(t -> t.toLowerCase(Locale.ROOT).equals(value.type()))) {
            throw new AtomValidationException("'" + text + "' has the type '" + value.type() + "', which is not one "
                    + "of " + types, "a media type of type " + String.join(", ", types));
        }
        List<String> suffixes = constraints.suffixes().orElse(null);
        if (suffixes != null && suffixes.stream().map(s -> s.toLowerCase(Locale.ROOT))
                .noneMatch(s -> value.subtype().equals(s) || value.subtype().endsWith("+" + s))) {
            throw new AtomValidationException("'" + text + "' has the subtype '" + value.subtype() + "', which is "
                    + "none of " + suffixes + " and has none of them as its suffix",
                    "a media type with the suffix " + String.join(", ", suffixes));
        }
        return value;
    }

    @Override
    public String write(MediaType value) {
        return value.toString();
    }

    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        return AtomTypeParser.isTextTarget(target) ? asWrittenText() : natural(MediaType.class, target);
    }
}
