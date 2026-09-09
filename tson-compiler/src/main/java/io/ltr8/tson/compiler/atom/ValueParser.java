package io.ltr8.tson.compiler.atom;

import java.util.Optional;

import io.ltr8.tson.base.unicode.IdentifierProfile;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.HostAtoms;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.base.BaseTypeResolver;
import io.ltr8.tson.atom.number.BaseValue;
import io.ltr8.tson.atom.number.NumberForm;
import io.ltr8.tson.atom.number.NumberForms;
import io.ltr8.tson.atom.number.NumberNarrowing;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Parses meta-kernel's {@code value} instance of the {@code unit} atom constructor (§4.2, §8.1) --
 * the "escape hatch primitive": per its own kernel doc, "the result of base type resolution
 * ([TSON-DATA] §4) applied to a source token, with no further interpretation... the host runtime is
 * responsible for type-checking values at use site." Unlike {@link IdentifierProfile} (raw lexical text,
 * unconstrained) this actually runs {@link BaseTypeResolver} -- boolean/number/string, §4.5's
 * fixed order -- and narrows the result to the natural Java host type each {@link BaseValue}
 * variant implies: {@link Boolean}, {@link BigInteger}/{@link BigDecimal} (or {@link Double} for the
 * two special numeric forms, {@code .nan}/{@code .inf}, which have no exact intermediate), or
 * {@link String}. <b>None of them is {@code null}</b>: §4 resolves three classes and absence is not
 * one of them, so a {@code value}-typed position holding {@code _} is an absent position rather than a
 * value this parser ever reads or writes. No caller-specified target -- {@code value} is declared to have
 * no constraint vocabulary and is explicitly "not narrowable" (its own kernel doc), so there is only
 * ever the one, natural representation.
 *
 * <p>See {@link IdentifierProfile}'s own Javadoc for why this class -- along with {@code void}'s own
 * {@code io.ltr8.tson.compiler.reader.VoidReader} -- exists as a separate,
 * name-keyed specialization rather than one shared {@code unit} compiler: the kernel's own text says
 * {@code value}/{@code token}/{@code void} are "distinguished by name and prose-level parsing
 * contract, not by schema shape."
 */
public final class ValueParser implements TokenAtomType<Object> {

    public static final ValueParser INSTANCE = new ValueParser();

    private ValueParser() {
    }

    @Override
    public Object read(TokenValue token) {
        return narrow(BaseTypeResolver.resolve(token));
    }

    /**
     * The {@code value} atom read at a slot whose bound host type is known -- [TSON-SCHEMA] §7.4's
     * constraint fields, and any other {@code value}-typed position a class binds to something base type
     * resolution does not produce.
     *
     * <p><b>Base type resolution is what a {@code value} slot is decoded by; it is not what the slot means.</b>
     * §4 resolves three classes -- boolean, number, string -- and a duration, a date and a UUID are none of
     * them, so {@code min: PT30M} on {@code duration_type} arrives here as the string {@code PT30M} and the
     * position's own host type is the only thing left that says what it was. Where the natural resolution
     * cannot be what the position holds, the token is re-read under the built-in atom that produces that host
     * type ({@link HostAtoms}) -- which is what meta.tn already describes the resolver as doing, and the same
     * rule {@code DecimalType} applies to a member of a {@code set<value>}.
     *
     * <p><b>Additive, never a re-interpretation.</b> A value the position can already hold is returned
     * untouched, and so is one a caller's own numeric narrowing will reach -- {@code min: 0x10} at a {@code
     * BigDecimal} slot stays the integer 16 and is narrowed by the caller, rather than being re-read under
     * {@code number}, whose grammar admits no based-integer form. Only a token whose natural resolution the
     * position could not hold under any narrowing reaches the atom, so nothing that reads today reads
     * differently, and what did not read at all now gets a verdict from the atom that owns the question:
     * {@code !number ^ { min: "abc" }} is refused by {@code number}, not by a cast.
     *
     * <p>A position whose host type no built-in produces is left alone, so a consumer's own class binding a
     * {@code value} field to their own type keeps whatever their bind context does with it.
     */
    @Override
    public Object read(TokenValue token, Class<?> target) {
        Object natural = read(token);
        if (target == null || AtomType.wrap(target).isInstance(natural) || narrowsTo(natural, target)) {
            return natural;
        }
        return HostAtoms.forHostType(target).<Object>map(atom -> atom.read(token.text())).orElse(natural);
    }

    /**
     * Whether a caller's own numeric narrowing turns {@code natural} into something {@code target} holds.
     * Asked rather than performed: the narrowing belongs to whoever declared the target, and doing it here
     * as well would narrow twice -- the trap {@code verifyFixed} already documents.
     */
    private static boolean narrowsTo(Object natural, Class<?> target) {
        if (!(natural instanceof Number)) {
            return false;
        }
        try {
            Object narrowed = natural instanceof java.math.BigInteger integer
                    ? NumberNarrowing.narrowIntegral(integer, target)
                    : natural instanceof BigDecimal decimal ? NumberNarrowing.narrowDecimal(decimal, target) : natural;
            return AtomType.wrap(target).isInstance(narrowed);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The {@code value} atom at a slot of known host type, for a caller that holds a reader rather than a token. */
    public static TokenAtomType<Object> at(Class<?> target) {
        return new TokenAtomType<Object>() {
            @Override
            public Object read(TokenValue token) {
                return INSTANCE.read(token, target);
            }

            @Override
            public Object read(TokenValue token, Class<?> ignored) {
                return INSTANCE.read(token, target);
            }

            @Override
            public String write(Object value) {
                return INSTANCE.write(value);
            }

            /** Already at a target: binding happens once, and this reader is the result of it. */
            @Override
            public Optional<AtomType<?>> boundTo(Class<?> ignored) {
                return Optional.empty();
            }
        };
    }

    private static Object narrow(BaseValue value) {
        return switch (value) {
            // Unreachable: BaseTypeResolver resolves a token, and no token is the absent sentinel.
            case BaseValue.AbsentValue ignored -> throw new IllegalStateException("base resolution produced absence");
            case BaseValue.BooleanValue b -> b.value();
            case BaseValue.StringValue s -> s.text();
            case BaseValue.NumberValue n -> narrowNumber(n.form());
        };
    }

    private static Object narrowNumber(NumberForm form) {
        if (form instanceof NumberForm.SpecialValueForm special) {
            return switch (special.kind()) {
                case NAN -> Double.NaN;
                case INFINITY -> special.sign().filter(s -> s == NumberForm.Sign.MINUS).isPresent()
                        ? Double.NEGATIVE_INFINITY
                        : Double.POSITIVE_INFINITY;
            };
        }
        if (form instanceof NumberForm.IntegerForm || form instanceof NumberForm.BasedIntegerForm) {
            return NumberForms.toBigInteger(form);
        }
        if (form instanceof NumberForm.FloatForm floatForm) {
            return NumberForms.toBigDecimal(floatForm);
        }
        throw new IllegalArgumentException("unrecognised number form: " + form);
    }

    @Override
    public String write(Object value) {
        return switch (value) {
            // `value` has no null inhabitant to write: absence is `_`, and an emitter writes it as absence.
            case null -> throw new IllegalArgumentException("the absent sentinel is not a 'value'; emit '_' instead");
            case Boolean b -> b.toString();
            case Double d when d.isNaN() -> ".nan";
            case Double d when d == Double.POSITIVE_INFINITY -> ".inf";
            case Double d when d == Double.NEGATIVE_INFINITY -> "-.inf";
            case BigInteger i -> i.toString();
            case BigDecimal d -> d.toString();
            case String s -> s;
            default -> throw new IllegalArgumentException("not a value this compiler ever produced: " + value);
        };
    }

    /**
     * {@code value} reads whatever the position's own host type says, which is exactly {@link #at}: the
     * uninterpreted atom has no host type of its own to offer, so the target chooses the family and this
     * hands back a reader over it. {@code RecordBindReader} reaches a {@code value} slot through its own
     * rebind rather than here, the two being one answer by two routes.
     *
     * <p>Written out rather than reached through {@code AtomTypeParser}'s helpers, which belong to the
     * built-in vocabulary: this atom reads a {@code TokenValue} where every family reads text, and its
     * answer is a whole family chosen by the target rather than any shape those helpers state.
     */
    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        return Optional.of(at(target));
    }
}
