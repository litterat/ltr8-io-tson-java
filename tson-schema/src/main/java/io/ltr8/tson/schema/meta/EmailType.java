package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.util.Optional;

/**
 * meta.tn1's {@code email_type} constructor (RFC 5322), composing {@code text_type}'s {@code
 * min_length}/{@code max_length}/{@code length}/{@code pattern}. Pure constraint values, no
 * parsing/validation behavior -- deliberately no {@code tson-parser} parser exists for this atom
 * yet (added as a {@code schema.meta}/{@link Atom} variant only, per explicit user direction, so
 * {@code !email_type {}}/{@code email}'s own resolution succeeds -- not to add real email
 * validation).
 *
 * <p>{@code spec} is a bare {@link String}, not nested inside {@link AtomSpecification} or typed
 * as a {@link java.net.URI} -- see {@link Cidr4Type}'s own Javadoc for why: a flat field is what
 * lets {@code tson-parser}'s compiled {@code Record*Reader}'s own schema-composed-default filling
 * actually populate it (unlike {@link UriType}/{@link RegexType}'s own nested {@code specification}
 * field, which predates that mechanism), and a bare {@code String} target is what an untyped,
 * unannotated string value can actually bind into ({@code java.net.URI} can't, without a {@code
 * !uri} type-ref).
 */
@Typename(name = "email_type")
public record EmailType(String spec, @Field("min_length") Optional<Integer> minLength,
                         @Field("max_length") Optional<Integer> maxLength,
                         Optional<Integer> length, Optional<String> pattern) implements Atom {

    /** {@code email => !email_type {}} -- the unconstrained email address, core.tn1's own {@code !email}. */
    public static final EmailType UNCONSTRAINED = new EmailType(
            "https://www.rfc-editor.org/rfc/rfc5322", Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());
}
