package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.util.List;
import java.util.Optional;

/**
 * The meta-kernel's {@code regex_type} constructor (Part 2 §5.7: {@code regex_type => ~text_type &
 * atom_specification & { spec: = "https://www.rfc-editor.org/rfc/rfc9485" } }) -- {@code text_type}'s
 * length and pattern facets plus {@code atom_specification}'s {@code spec}, pinned to RFC 9485, the
 * I-Regexp specification. Pure constraint values, no parsing/validation behavior -- {@code
 * tson-compiler}'s {@code RegexParser} holds one of these and does the actual reading/writing.
 *
 * <p><b>Every field is flat, mirroring the resolved shape rather than the composition that produced
 * it</b> -- composition always flattens (§5.8), so an instance's wire record carries {@code
 * min_length}/{@code max_length}/{@code length}/{@code pattern}/{@code spec} side by side, with no
 * sub-record anywhere. A component nesting any of them under a name the wire doesn't have receives
 * nothing at all: {@code tson-compiler}'s compiled {@code Record*Reader} fills a field, including a
 * {@code REQUIRED_FIXED} field's schema-composed default, under its own schema field name. This is
 * why the shape here is field-for-field {@link EmailType}'s -- {@code email_type} is declared by the
 * identical composition and differs only in which document {@code spec} is fixed to.
 *
 * <p>{@code spec} is a bare {@link String}, not a {@link java.net.URI}: the schema writes it as an
 * untyped, unannotated quoted value ({@code spec: = "https://..."}), and {@code AtomBinder} converts a
 * string into {@code URI} only through the built-in-vocabulary type-ref path ({@code !uri "..."}),
 * never the untyped one this field goes through. {@code pattern} is the regex's own source text for
 * the reason {@link TextType#pattern()} records.
 *
 * <p>{@code regex => !regex_type {}} is a constructor-application instance (§5.5) whose resolved body
 * is exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "regex_type")
public record RegexType(String spec, @Field("min_length") Optional<Integer> minLength,
                        @Field("max_length") Optional<Integer> maxLength,
                        Optional<Integer> length, Optional<String> pattern) implements Atom {

    /** {@code regex => !regex_type {}} -- the unconstrained regex type. */
    public static final RegexType UNCONSTRAINED = new RegexType(
            "https://www.rfc-editor.org/rfc/rfc9485", Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

    /** The {@code text_type} facets this composes, as the {@link TextType} that owns their comparison rules. */
    public TextType textConstraints() {
        return new TextType(minLength, maxLength, length, pattern);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A regex IS-A piece of text, so the narrowing rule is {@link TextType}'s own, applied to the
     * facets this composes. {@code spec} is {@code REQUIRED_FIXED} to RFC 9485 and cannot move.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof RegexType other)) {
            return List.of("refines a regex with " + refined.getClass().getSimpleName());
        }
        return textConstraints().constraintsCheck(other.textConstraints());
    }

    /** {@inheritDoc} <p>The length facets this composes, judged by {@link TextType#coherenceCheck} that owns them. */
    @Override
    public List<String> coherenceCheck() {
        return textConstraints().coherenceCheck();
    }
}
