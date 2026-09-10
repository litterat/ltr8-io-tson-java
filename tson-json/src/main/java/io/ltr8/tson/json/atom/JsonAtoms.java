package io.ltr8.tson.json.atom;

import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassBridge;
import io.ltr8.tson.atom.AtomRefusal;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.atom.HostAtoms;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.stream.JsonEvent;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * One JSON leaf into one host value, at a {@link DataClassAtom} position.
 *
 * <p>The seed of this module's atom vocabulary, and where [TSON-JSON] §5's per-family readers land when
 * the schema-directed decode arrives: §5.1 hands a string's content to the atom's own parser exactly as a
 * TSON quoted token's text would be, so each family needs a reader here and none of them belongs in a
 * reader that walks structure. Unexported, on the same terms as {@code tson-compiler}'s own {@code atom}
 * package -- a consumer names a value or a reader, never a parser.
 *
 * <p><b>The target picks the parser; the JSON kind decides only whether the content is admitted.</b> That
 * is [TSON-JSON] §4.1's schema-directed reading with the class standing in for the schema: there is no
 * untyped position in this encoding and no base type resolution under it (§5.7 says so outright), so
 * nothing here inspects a value to guess what it "is". A {@code JsonEvent.NumberValue} at an {@code int}
 * component faces {@code int32}'s own contract; the same event at a {@code BigDecimal} component faces
 * {@code number}'s and keeps every digit. The dispatch is on the target for a reason beyond symmetry --
 * §5.4's approximate families admit a JSON number <em>and</em> a JSON string through one parser, which a
 * switch on the leaf kind cannot express.
 *
 * <p><b>Exact or an error, never a silent round</b> (§3.1). The exact tier's own contracts do it: {@code
 * 1.5} at an {@code int} is a float form where §5.3 admits only an integer one, and {@code 2147483648} is
 * outside {@code int32}'s range -- two different refusals for two different reasons, where narrowing one
 * {@code BigDecimal} made them one. {@code float} and {@code double} are the exception and are meant to be:
 * rounding onto the binary grid is the approximate families' own contract (§5.4).
 *
 * <p><b>A bridged atom binds its serial type, then crosses.</b> {@code DataClassAtom.dataClass()} is the
 * type on the wire and {@code typeClass()} the one the class wants, so a bridged component is read as the
 * type its bridge declares and handed to {@code toObject}. That is how {@code tson-bind}'s own bridges --
 * an enum's, a {@code Pattern}'s -- are covered without naming one of them.
 *
 * <p><b>A string-content family is read by its own parser</b> (§5.1): the string's content is handed to the
 * atom's parser exactly as a TSON quoted token's text would be, and the target class picks which parser,
 * there being no type-ref to name one. {@link HostAtoms#forStringContentHostType} is that lookup, and it is
 * the same index {@code DataClassObjectReader} consults on the text side when no type-ref supplies a name
 * -- one vocabulary, so a {@code UUID} component reads alike whichever encoding carried it. It is restricted
 * to §5.6's string-content families on purpose: the numeric families read from a JSON number and not from a
 * string, and letting {@code "123"} become a {@code BigInteger} because a field is declared one would let a
 * class overrule the encoding's own kinds.
 *
 * <p><b>The numeric families go through the same door</b>, by {@link HostAtoms#forNumberContentHostType} --
 * the inverse of {@code IntegerParser.hostType}, so {@code int} reaches {@code int32} and {@code double}
 * reaches {@code float64}. What that buys over narrowing a host value the encoding chose: §5.3's contract
 * rejection is a rejection ({@code 1.0} at an {@code int} is not an integer, as the token {@code 1.0} is
 * not in text), §5.4's string-spelled special values reach the family that parses them, the atom's own
 * {@code expected} reaches the diagnostic ({@code >= -128 and <= 127} rather than the target's Java name),
 * and a refinement's {@code allow_nan} or {@code multiple_of} has somewhere to be honoured when the
 * schema-directed decode lands.
 *
 * <p><b>There is no enum rule here, and that is not an omission.</b> {@code tson-bind} binds every plain
 * Java enum through {@code EnumStringBridge}, so an enum component arrives as a {@code String} atom whose
 * bridge does the lookup -- a rule matching constants by name in this class would never be reached, and a
 * rule that is never reached still has to be read by everyone who comes after it.
 */
public final class JsonAtoms {

    private JsonAtoms() {
    }

    /** {@code leaf} at {@code atom}'s type, bridge applied. {@code null} only where the leaf was JSON null. */
    public static Object bind(JsonReadContext ctx, JsonEvent leaf, DataClassAtom atom) {
        Class<?> wire = atom.dataClass();
        Object value = leaf instanceof JsonEvent.NullValue ? null : bindTo(ctx, leaf, wire);
        DataClassBridge bridge = atom.bridge().orElse(null);
        if (bridge == null || value == null) {
            return value;
        }
        try {
            return bridge.toObject().invoke(value);
        } catch (Throwable e) {
            // The bridge's own rule refusing the content -- an enum constant that is not one, a malformed
            // UUID. A constraint on the value, which is what ATOM_CONSTRAINT_VIOLATION names.
            ctx.report(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, "'%s' is not a %s: %s"
                    .formatted(text(leaf), atom.typeClass().getSimpleName(), e.getMessage()),
                    "a " + atom.typeClass().getSimpleName(), text(leaf));
            return null;
        }
    }

    /**
     * <b>The target picks the parser; the JSON kind decides only whether the content is admitted.</b>
     * That is §4.1 read literally -- there is no untyped position in this encoding and no base type
     * resolution under it (§5.7 says so outright) -- and it is why the target is the outer dispatch here.
     * A switch on the leaf kind cannot express §5.4, where one parser serves two kinds.
     *
     * <p>The three targets answered by kind alone come first: they are the encoding's own rather than any
     * family's, {@code text} accepting every JSON string (§5.6) and {@code boolean} being the general enum
     * rule applied (§5.2). {@code Object} is here for the {@code value} position of §5.7, which has no
     * bind-path representation yet -- {@code tson-bind} refuses an {@code Object} component outright.
     */
    private static Object bindTo(JsonReadContext ctx, JsonEvent leaf, Class<?> target) {
        if (target == boolean.class || target == Boolean.class) {
            return leaf instanceof JsonEvent.BooleanValue bool ? bool.value() : mismatch(ctx, describe(leaf), target);
        }
        if (target == String.class || target == CharSequence.class || target == Object.class) {
            return untyped(ctx, leaf, target);
        }
        if (target == char.class || target == Character.class) {
            return character(ctx, leaf, target);
        }
        Optional<AtomType<?>> numeric = HostAtoms.forNumberContentHostType(target);
        if (numeric.isPresent()) {
            return number(ctx, leaf, numeric.get(), target);
        }
        Optional<AtomType<?>> stringContent = HostAtoms.forStringContentHostType(target);
        if (stringContent.isPresent()) {
            return leaf instanceof JsonEvent.StringValue string
                    ? content(ctx, stringContent.get(), string.value(), target)
                    : mismatch(ctx, describe(leaf), target);
        }
        return mismatch(ctx, describe(leaf), target);
    }

    /**
     * §5.3 and §5.4, which differ in one thing: an <b>approximate</b> position admits a JSON string as well
     * as a JSON number, the two infinities and NaN having no JSON number spelling and encoding as
     * {@code ".inf"}/{@code "-.inf"}/{@code ".nan"} -- [TSON-DATA] §7.6's own productions, so the same
     * parser reads them and no third spelling of infinity enters the series. Both kinds reach one
     * {@link #content} call, which is the whole reason the target is dispatched on first.
     *
     * <p>Which families are approximate is <b>this encoding's</b> question and not the vocabulary's, so it
     * is answered here by the two Java types §5.4 names rather than by asking an {@link AtomType} about
     * JSON kinds it should know nothing about.
     */
    private static Object number(JsonReadContext ctx, JsonEvent leaf, AtomType<?> family, Class<?> target) {
        if (leaf instanceof JsonEvent.NumberValue value) {
            return content(ctx, family, value.literal(), target);
        }
        if (leaf instanceof JsonEvent.StringValue special && approximate(target)) {
            return content(ctx, family, special.value(), target);
        }
        return mismatch(ctx, describe(leaf), target);
    }

    /** §5.4's two families, by the host types they read to. */
    private static boolean approximate(Class<?> target) {
        return target == float.class || target == Float.class || target == double.class || target == Double.class;
    }

    /** A position whose target names no family: the JSON kind is the whole of what it can say. */
    private static Object untyped(JsonReadContext ctx, JsonEvent leaf, Class<?> target) {
        return switch (leaf) {
            case JsonEvent.StringValue string -> string.value();
            case JsonEvent.BooleanValue bool when target == Object.class -> bool.value();
            // §5.7 reads a number without fraction or exponent as an integer and one with either as a
            // float; this keeps every digit instead, which is the wider promise and the one §5.3 makes.
            case JsonEvent.NumberValue value when target == Object.class -> new BigDecimal(value.literal());
            default -> mismatch(ctx, describe(leaf), target);
        };
    }

    private static Object character(JsonReadContext ctx, JsonEvent leaf, Class<?> target) {
        if (!(leaf instanceof JsonEvent.StringValue string)) {
            return mismatch(ctx, describe(leaf), target);
        }
        if (string.value().length() != 1) {
            ctx.report(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                    "a char takes a one-character string, not %d".formatted(string.value().length()),
                    "one character", string.value().length() + " characters");
            return null;
        }
        return string.value().charAt(0);
    }

    /**
     * §5.1's contract boundary: the family parses, and this only carries its refusal across.
     *
     * <p>Which code a refusal carries is {@link AtomRefusal}'s and not this reader's -- §8.1 files a
     * contract rejection and a range violation in different categories, and a copy of that mapping here is
     * how this encoding and the text one come to disagree about the same token.
     */
    private static Object content(JsonReadContext ctx, AtomType<?> family, String value, Class<?> target) {
        try {
            return family.boundTo(target).orElseThrow(() -> new AtomValidationException(
                    "cannot represent " + value + " as " + target,
                    "a value representable as " + target.getSimpleName())).read(value);
        } catch (RuntimeException e) {
            AtomRefusal refusal = AtomRefusal.of(e, value, target);
            ctx.report(refusal.code(), refusal.message(), refusal.expected(), refusal.actual());
            return null;
        }
    }

    /** A JSON kind the position's own type does not admit -- §5's wrong-form validation error. */
    private static Object mismatch(JsonReadContext ctx, String found, Class<?> target) {
        ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                "%s cannot be read into %s".formatted(found, target.getSimpleName()),
                target.getSimpleName(), found);
        return null;
    }

    /** How a leaf names itself in a message. */
    public static String describe(JsonEvent event) {
        return switch (event) {
            case JsonEvent.ObjectStart ignored -> "an object";
            case JsonEvent.ArrayStart ignored -> "an array";
            case JsonEvent.StringValue ignored -> "a string";
            case JsonEvent.NumberValue ignored -> "a number";
            case JsonEvent.BooleanValue ignored -> "a boolean";
            case JsonEvent.NullValue ignored -> "null";
            default -> "the end of a value";
        };
    }

    /** A leaf's own text, for a message that wants to show what arrived. */
    private static String text(JsonEvent event) {
        return switch (event) {
            case JsonEvent.StringValue string -> string.value();
            case JsonEvent.NumberValue number -> number.literal();
            case JsonEvent.BooleanValue bool -> Boolean.toString(bool.value());
            default -> describe(event);
        };
    }
}
