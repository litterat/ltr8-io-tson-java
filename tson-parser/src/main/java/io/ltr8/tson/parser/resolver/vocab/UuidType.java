package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * meta-kernel's {@code uuid_type} constructor (§5.5's {@code uuid} atom, RFC 9562). A pure format
 * check (§5.2: "the remaining atoms are pure format checks") unless {@code version} is set, which
 * no built-in instance does -- {@code uuid => !uuid_type {}} in core.tn1 is fully unconstrained.
 *
 * <p>{@link #read} validates the token's shape itself (RFC 9562's canonical 8-4-4-4-12 hex-and-
 * hyphen grouping) before calling {@link UUID#fromString}, rather than trusting that method's own
 * validation -- {@code UUID.fromString} is materially more lenient than the canonical format: {@code
 * UUID.fromString("1-2-3-4-5")} succeeds (producing {@code 00000001-0002-0003-0004-000000000005}),
 * and a group one hex digit short still parses by silently reinterpreting where the groups fall.
 * Confirmed empirically, not assumed, before writing this -- the same "validate shape myself, then
 * delegate to the JDK only once shape is already confirmed" pattern as hex-float ({@link
 * io.ltr8.tson.parser.resolver.NumberGrammar#isHexFloat}).
 *
 * <p>Has exactly one legitimate host representation ({@link UUID} itself), so like {@link
 * RationalType}/{@link ComplexType} this doesn't override {@link #read(TokenValue, Class)} --
 * {@link AtomType}'s default already covers it. Unlike {@code Rational}/{@code Complex}, {@code
 * UUID} isn't a Java record, so it doesn't collide with {@code tson-bind}'s record auto-detection --
 * but it also isn't {@code @Atom}-annotatable (it's a JDK class), so {@code DataBindContext} now
 * pre-registers it as a bridge-less atom directly, the same way it already does for {@code
 * java.util.Date}, rather than requiring every caller to register it themselves.
 */
public record UuidType(Optional<Integer> version) implements AtomType<UUID> {

    /** {@code uuid => !uuid_type {}} -- the unconstrained UUID, §5.5's {@code !uuid}. */
    public static final UuidType UNCONSTRAINED = new UuidType(Optional.empty());

    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Override
    public UUID read(TokenValue token) {
        String text = token.text();
        if (!UUID_TEXT.matcher(text).matches()) {
            throw new AtomParseException(
                    "'" + text + "' is not a valid UUID -- expected RFC 9562's 8-4-4-4-12 hex-and-hyphen form (§5.5)");
        }
        UUID value = UUID.fromString(text);
        version.ifPresent(v -> {
            if (value.version() != v) {
                throw new AtomValidationException(
                        "'" + text + "' is version " + value.version() + ", expected version " + v);
            }
        });
        return value;
    }
}
