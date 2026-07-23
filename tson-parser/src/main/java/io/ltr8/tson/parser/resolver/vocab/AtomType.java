package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;

/**
 * A built-in vocabulary atom's parsing contract (§5.2): "which tokens it accepts, and what host
 * value results." One implementation per meta-kernel/meta type constructor that the built-in
 * vocabulary (§5) actually surfaces as a schemaless annotation -- e.g. {@code integer_type} backs
 * {@link IntegerParser}. A single instance is a fully-parameterized *instance* of that constructor,
 * exactly mirroring the schema's own constructor/instance split: {@code int32}'s entry in the
 * built-in map is one {@code IntegerParser} constructed with {@code size = {bits: 32, signed: true}},
 * the same way {@code core.tn1} writes {@code int32 => !integer ^ { size: { bits: 32 signed: true
 * } } }.
 *
 * <p>{@link #read(TokenValue)} returns the atom's own canonical host value (this atom's natural
 * representation -- a {@link java.math.BigInteger} narrowed to whatever primitive its own declared
 * width actually needs for {@code IntegerParser}, a {@link java.time.LocalDate} for {@code date},
 * etc.) for a caller with no specific target in mind.
 *
 * <p>{@link #read(TokenValue, Class)} is for a caller that *does* know its target representation
 * (e.g. {@code tson-mapper} binding to a field declared {@code int}) and wants it directly, without
 * a caller-side table of which method name produces which primitive for which atom type -- that
 * knowledge stays inside each {@code AtomType} implementation instead of leaking into every caller.
 * The default here covers atoms with exactly one legitimate host representation (most of them --
 * {@code uuid}, {@code date}, ...): read the natural value and require the target to accept it.
 * Atoms with more than one legitimate representation (the numeric family) override it to narrow
 * directly, sharing the target-matching logic in {@link
 * io.ltr8.tson.parser.resolver.NumberNarrowing} with {@code tson-mapper}'s untyped-number binding
 * rather than duplicating it -- this interface still has no dependency on any binding library;
 * {@code Class<?>} is a bare JDK type, not {@code tson-bind}'s {@code DataClassAtom}.
 *
 * <p>{@link #write(Object)} is {@link #read(TokenValue)}'s inverse: given a natural host value,
 * the token text that would read back to an equivalent value (never quoted, never carrying a
 * type-ref -- both are a caller's structural concern, not this atom's). Lives here rather than in
 * whichever binding library happens to be writing TSON text today so that a caller extending the
 * vocabulary with its own {@code AtomType} gets both directions from one implementation, the same
 * way every built-in one already does.
 */
public interface AtomType<T> {

    T read(TokenValue token) throws AtomParseException, AtomValidationException;

    default Object read(TokenValue token, Class<?> target) throws AtomParseException, AtomValidationException {
        T value = read(token);
        if (!wrap(target).isInstance(value)) {
            throw new AtomValidationException("cannot represent " + value + " as " + target);
        }
        return value;
    }

    String write(T value);

    private static Class<?> wrap(Class<?> type) {
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }
}
