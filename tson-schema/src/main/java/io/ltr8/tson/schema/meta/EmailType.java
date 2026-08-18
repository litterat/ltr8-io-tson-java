package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.util.List;
import java.util.Optional;

/**
 * meta.tn1's {@code email_type} constructor (RFC 5322), composing {@code text_type}'s {@code
 * min_length}/{@code max_length}/{@code length}/{@code pattern}. Pure constraint values, no
 * parsing/validation behavior -- {@code tson-compiler}'s {@code EmailParser} holds one of these and does
 * the actual reading/writing.
 *
 * <p>{@code spec} is flat and a bare {@link String}, never a {@link java.net.URI} -- see {@link
 * Cidr4Type}'s own Javadoc for both halves of why. {@link RegexType} is this record's exact twin:
 * {@code regex_type} is declared by the identical composition and differs only in the document {@code
 * spec} is fixed to.
 */
@Typename(name = "email_type")
public record EmailType(String spec, @Field("min_length") Optional<Integer> minLength,
                         @Field("max_length") Optional<Integer> maxLength,
                         Optional<Integer> length, Optional<String> pattern) implements Atom {

    /** {@code email => !email_type {}} -- the unconstrained email address, core.tn1's own {@code !email}. */
    public static final EmailType UNCONSTRAINED = new EmailType(
            "https://www.rfc-editor.org/rfc/rfc5322", Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

    /**
     * {@inheritDoc}
     *
     * <p>The same length facets {@link TextType} carries, checked the same way -- {@link #length}
     * pins both ends and folds into the range the other two are compared against. {@link #pattern}
     * is undecidable here (see {@link TextType#constraintsCheck}) and {@code spec} is fixed by the
     * schema.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof EmailType other)) {
            return List.of("refines an email with " + refined.getClass().getSimpleName());
        }
        return new TextType(minLength, maxLength, length, pattern)
                .constraintsCheck(new TextType(other.minLength, other.maxLength, other.length, other.pattern));
    }
}
