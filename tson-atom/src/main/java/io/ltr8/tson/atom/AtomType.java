package io.ltr8.tson.atom;

import java.util.Optional;

import io.ltr8.tson.atom.parser.IntegerParser;

/**
 * A built-in vocabulary atom's parsing contract (§5.2): "which tokens it accepts, and what host
 * value results." One implementation per meta-kernel/meta type constructor that the built-in
 * vocabulary (§5) actually surfaces as a schemaless annotation -- e.g. {@code integer_type} backs
 * {@link IntegerParser}. A single instance is a fully-parameterized *instance* of that constructor,
 * exactly mirroring the schema's own constructor/instance split: {@code int32}'s entry in the
 * built-in map is one {@code IntegerParser} constructed with {@code size = {bits: 32, signed: true}},
 * the same way {@code core.tn} writes {@code int32 => !integer ^ { size: { bits: 32 signed: true
 * } } }.
 *
 * <p><b>It reads text, not a token.</b> Every family but two is a function of the content alone, which is
 * what lets one vocabulary serve both encodings: [TSON-JSON] §5.1 hands a JSON string's content to the
 * atom's own parser exactly as a TSON quoted token's text would be. The two that need the lexical form --
 * the kernel's {@code value}, whose [TSON-DATA] §4.4 rule is that a quoted token is a string, and
 * {@code Token}, which records the spelling [TSON-SCHEMA] §8's resolved form carries -- are the text
 * encoding's own and live there, behind {@code TokenAtomType}.
 *
 * <p>{@link #read(String)} returns the atom's own canonical host value (this atom's natural
 * representation -- a {@link java.math.BigInteger} narrowed to whatever primitive its own declared
 * width actually needs for {@code IntegerParser}, a {@link java.time.LocalDate} for {@code date},
 * etc.) for a caller with no specific target in mind.
 *
 * <p>{@link #boundTo(Class)} is for a caller that *does* know its target representation
 * (e.g. {@code TsonObjectReader} binding to a field declared {@code int}) and wants it directly, without
 * a caller-side table of which method name produces which primitive for which atom type -- that
 * knowledge stays inside each {@code AtomType} implementation instead of leaking into every caller.
 * The default here covers atoms with exactly one legitimate host representation (most of them --
 * {@code uuid}, {@code date}, ...): read the natural value and require the target to accept it.
 * Atoms with more than one legitimate representation (the numeric family) override it to narrow
 * directly, sharing the target-matching logic in {@link
 * io.ltr8.tson.atom.number.NumberNarrowing} with {@code TsonObjectReader}'s untyped-number binding
 * rather than duplicating it -- this interface still has no dependency on any binding library;
 * {@code Class<?>} is a bare JDK type, not {@code tson-bind}'s {@code DataClassAtom}.
 *
 * <p>{@link #write(Object)} is {@link #read(String)}'s inverse: given a natural host value,
 * the token text that would read back to an equivalent value (never quoted, never carrying a
 * type-ref -- both are a caller's structural concern, not this atom's). Lives here rather than in
 * whichever binding library happens to be writing TSON text today so that a caller extending the
 * vocabulary with its own {@code AtomType} gets both directions from one implementation, the same
 * way every built-in one already does.
 */
public interface AtomType<T> {

    /**
     * This family reading into {@code target}, or empty where no value of it ever reaches one.
     *
     * <p><b>The target is bound once, where the reader is built, not carried into every read.</b> A schema
     * fixes which family reads a position and a bound class fixes what that position must produce, so the
     * two meet when the reader is compiled -- and an empty answer there is a disagreement reported before
     * any document exists, rather than a cast failing inside a constructor on the first one.
     *
     * <p><b>Every family answers; there is no default.</b> One that admitted whatever it was handed would
     * give the check nothing to work with. The answer is rarely hard -- the natural host type, and for a
     * family whose wire form is text, the spelling it validated -- and a family that genuinely reads into
     * any class, one embedding another format say, states that too and is the clearer for saying it.
     */
    Optional<AtomType<?>> boundTo(Class<?> target);

    T read(String text) throws AtomParseException, AtomValidationException;

    String write(T value);

    /**
     * {@code type}'s boxed form, so an {@code isInstance} check answers the same for {@code int} as for
     * {@link Integer}. Shared with {@code ValueParser}, which asks the same question of a bound slot's own
     * host type rather than of a caller-supplied target.
     */
    static Class<?> wrap(Class<?> type) {
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
