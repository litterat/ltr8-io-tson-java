package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.base.unicode.Nfc;
import io.ltr8.tson.base.unicode.Normalization;
import io.ltr8.tson.regex.TsonRegex;
import io.ltr8.tson.schema.meta.TextType;
import java.util.Optional;

/**
 * Parses and validates against meta-kernel's {@code text_type} constructor -- the Unicode code
 * point sequence type every other text-shaped atom in this package composes with ({@code
 * UriParser} already does; {@code RegexParser} does explicitly, via a {@code TextType} field,
 * rather than duplicating its constraint checks). Holds a {@link TextType} -- the pure constraint
 * values, unchanged by this split -- rather than declaring those fields itself.
 *
 * <p><b>{@code !text} is §5.5's unconstrained text atom</b>: every token accepted, the host value the
 * token's text. It adds nothing an unannotated token's base resolution (§4.4) does not already give, and
 * that is the point -- it lets the string case be asserted, so a quoted numeric under {@code !text} is
 * unambiguously the string rather than a number that happened to be quoted.
 *
 * <p><b>The value is the token's text in the type's {@code normalization} form</b>, and the facets judge that
 * value: under {@code NFKC_CASEFOLD}, {@code Content-Type} reads as {@code content-type} and matches a member
 * written either way. A refusal quotes the token as written and then the value it was read as
 * ({@link #subject}), since the written spelling is what a reader of the message has to find.
 *
 * <p><b>No reverse mapping.</b> {@code VocabularyAtoms} maps a host class to the name a writer annotates it
 * with, and this one's host class is {@code String} -- what both writers emit bare. An entry there would put
 * {@code !text} on every string in every document.
 */
public record TextParser(TextType constraints) implements AtomTypeParser<String> {

    /** The §5.5 annotation name this atom is reached by. */
    public static final String TYPENAME = "text";

    /** {@code text => !text_type {}} -- the unconstrained text type. */
    public static final TextParser UNCONSTRAINED = new TextParser(TextType.UNCONSTRAINED);

    public TextParser(Optional<Integer> minLength, Optional<Integer> maxLength, Optional<Integer> length,
                       Optional<String> pattern) {
        this(new TextType(minLength, maxLength, length, pattern));
    }

    /**
     * The value {@code text} decodes to -- the text put into the type's {@code normalization} form
     * ([TSON-SCHEMA] §5.5) -- once every facet has judged that value.
     */
    @Override
    public String read(String text) {
        String value = constraints.normalization().apply(text);
        validate(value, subject(text, value, constraints.normalization()));
        return value;
    }

    @Override
    public String write(String value) {
        return value;
    }

    /**
     * The facets over a value already in the type's form, a refusal naming it as {@code subject};
     * {@code IdentifierParser} runs its profile between.
     */
    void validate(String text, String subject) {
        checkLengths(text, subject, constraints.length(), constraints.minLength(), constraints.maxLength());
        // The pattern is I-Regexp (RFC 9485), matched via tson-regex (linear-time, ReDoS-safe), not
        // java.util.regex; it was already validated well-formed when the schema resolved (see RegexParser).
        constraints.pattern().ifPresent(p -> {
            if (!TsonRegex.parse(p).matches(text)) {
                throw new AtomValidationException(subject + " does not match the required pattern " + p,
                        "matching " + p);
            }
        });
        // Last, as on the numeric tiers: a member set names the whole value space, so where it is present the
        // other facets hold vacuously and their messages are the less useful of the two.
        constraints.normalizedMembers().ifPresent(members -> {
            if (!members.contains(Nfc.of(text))) {
                throw new AtomValidationException(
                        subject + " is not a member of this type -- expected one of " + members,
                        "one of (" + String.join(", ", members) + ")");
            }
        });
    }

    /** This family already reads to text, so a string target is its own value and nothing else is. */
    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        return natural(String.class, target);
    }


    /**
     * How a refusal names a token: {@code 'written'}, and where the type's form changed it, the value it was
     * read as -- {@code 'PUT' (read as 'put' under NFKC_CASEFOLD)}. The facets judge the value, so the message
     * states it; the written spelling leads because it is the text a reader has to find in the document, and a
     * repair of a generated document starts from what was emitted.
     */
    static String subject(String written, String value, Normalization form) {
        return written.equals(value) ? "'" + written + "'"
                : "'" + written + "' (read as '" + value + "' under " + form + ")";
    }

    /**
     * {@code text_type}'s three length facets over {@code text}, a refusal naming it as {@code subject}, shared
     * by every family that composes them. A length counts code points, as {@code text_type} says, so a character
     * outside the Basic Multilingual Plane is one character and not the two UTF-16 units {@link String#length}
     * would count.
     */
    static void checkLengths(String text, String subject, Optional<Integer> length, Optional<Integer> minLength,
                             Optional<Integer> maxLength) {
        if (length.isEmpty() && minLength.isEmpty() && maxLength.isEmpty()) {
            return;
        }
        int count = text.codePointCount(0, text.length());
        if (length.isPresent() && count != length.get()) {
            throw new AtomValidationException(
                    subject + " is " + count + " characters, expected exactly " + length.get(),
                    "exactly " + length.get() + " characters");
        }
        if (minLength.isPresent() && count < minLength.get()) {
            throw new AtomValidationException(
                    subject + " is " + count + " characters, less than the minimum " + minLength.get(),
                    "at least " + minLength.get() + " characters");
        }
        if (maxLength.isPresent() && count > maxLength.get()) {
            throw new AtomValidationException(
                    subject + " is " + count + " characters, more than the maximum " + maxLength.get(),
                    "at most " + maxLength.get() + " characters");
        }
    }

}
