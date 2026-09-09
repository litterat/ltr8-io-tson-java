package io.ltr8.tson.atom;

import java.util.Optional;
import java.util.function.Function;

import io.ltr8.tson.atom.number.NumberNarrowing;
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
 * <p>{@link #read(String, Class)} is for a caller that *does* know its target representation
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

    T read(String text) throws AtomParseException, AtomValidationException;

    /**
     * This family reading into {@code target}, or empty where no value of it ever reaches one.
     *
     * <p><b>The target is bound once, where the reader is built, not carried into every read.</b> A schema
     * fixes which family reads a position and a bound class fixes what that position must produce, so the
     * two meet when the reader is compiled -- and an empty answer there is a disagreement reported before
     * any document exists, rather than a cast failing inside a constructor on the first one.
     *
     * <p><b>Every family answers; there is no default.</b> A family that admitted whatever it was handed
     * would give the check nothing to work with, and the answer is rarely hard: {@link #natural} for a value
     * that reaches its target unchanged, {@link #asWrittenText} for a wire form that is text, {@link #bound}
     * or {@link #converting} where the reading has to be converted. A family that genuinely reads into any
     * class -- one embedding another format, say -- says so with {@link #bound} and is the clearer for it.
     */
    Optional<AtomType<?>> boundTo(Class<?> target);

    /**
     * A {@link #boundTo} answer for a family whose value reaches {@code target} unchanged -- the natural
     * host type, and the primitive of a boxed one. The common shape, so that stating it is a line.
     */
    default Optional<AtomType<?>> natural(Class<?> naturalType, Class<?> target) {
        return wrap(target).isAssignableFrom(wrap(naturalType)) ? Optional.of(this) : Optional.empty();
    }

    /**
     * A {@link #boundTo} answer stated as the two directions themselves, for a family whose reading into a
     * target is not its own value converted -- the numeric families, which narrow from the token's text
     * rather than from the host value they would otherwise produce.
     */
    default <R> Optional<AtomType<?>> bound(Function<String, R> read, Function<R, String> write) {
        return Optional.of(new BoundAtom<>(read, write));
    }

    default <R> Optional<AtomType<?>> converting(Function<T, R> render, Function<R, String> parse) {
        AtomType<T> self = this;
        return bound(text -> render.apply(self.read(text)), parse);
    }

    /**
     * {@link #converting} to the text this family read, for a string target. Every family whose wire form is
     * text can hand back the spelling it validated: the value is checked first and only then rendered, so a
     * text target chooses the representation and never the rules.
     */
    default Optional<AtomType<?>> asWrittenText() {
        return converting(value -> write(value), text -> text);
    }

    /** Whether {@code target} is one of the two classes a string-valued reading may land in. */
    static boolean isTextTarget(Class<?> target) {
        return target == String.class || target == CharSequence.class;
    }

    default Object read(String text, Class<?> target) throws AtomParseException, AtomValidationException {
        T value = read(text);
        if (!wrap(target).isInstance(value)) {
            throw new AtomValidationException("cannot represent " + value + " as " + target,
                    "a value representable as " + target.getSimpleName());
        }
        return value;
    }

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
