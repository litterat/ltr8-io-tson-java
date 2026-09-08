package io.ltr8.tson.json.atom;

import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassBridge;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.atom.HostAtoms;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.reader.JsonReadContext;
import io.ltr8.tson.json.stream.JsonEvent;

import java.math.BigDecimal;
import java.math.BigInteger;
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
 * <p><b>The target type decides, never the JSON kind.</b> A {@code JsonEvent.NumberValue} at an
 * {@code int} component is an {@code int}; the same event at a {@code BigDecimal} component keeps every
 * digit. That is [TSON-JSON] §4.1's schema-directed reading with the class standing in for the schema,
 * and it is why nothing here inspects a value to guess what it "is".
 *
 * <p><b>Exact or an error, never a silent round</b> (§3.1). Every integral narrowing goes through
 * {@link BigDecimal}'s exact conversions, so {@code 1.5} at an {@code int} and {@code 2147483648} at an
 * {@code int} both fail rather than truncating or wrapping. {@code float} and {@code double} are the
 * exception and are meant to be: rounding onto the binary grid is the approximate families' own
 * contract (§5.4).
 *
 * <p><b>A bridged atom binds its serial type, then crosses.</b> {@code DataClassAtom.dataClass()} is the
 * type on the wire and {@code typeClass()} the one the class wants, so a bridged component is read as the
 * type its bridge declares and handed to {@code toObject}. That is how {@code tson-bind}'s own bridges --
 * an enum's, a {@code Pattern}'s -- are covered without naming one of them.
 *
 * <p><b>A string-content family is read by its own parser</b> (§5.1): the string's content is handed to the
 * atom's parser exactly as a TSON quoted token's text would be, and the target class picks which parser,
 * there being no type-ref to name one. {@link HostAtoms#forStringContentHostType} is that lookup, and it is
 * the same index {@code SchemalessObjectReader} consults on the text side when no type-ref supplies a name
 * -- one vocabulary, so a {@code UUID} component reads alike whichever encoding carried it. It is restricted
 * to §5.6's string-content families on purpose: the numeric families read from a JSON number and not from a
 * string, and letting {@code "123"} become a {@code BigInteger} because a field is declared one would let a
 * class overrule the encoding's own kinds.
 *
 * <p><b>The numeric families do not go through their parsers yet</b>, and the difference shows: a number is
 * identified and then narrowed here rather than read by the contract of the family the target names, so
 * {@code 1.0} at an {@code int} binds as {@code 1} where §5.3 makes it a contract rejection, and {@code
 * ".nan"} at a {@code double} is refused where §5.4 admits it. {@code BACKLOG.md}'s "JSON encoding" section
 * carries the reverse index that closes it.
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

    private static Object bindTo(JsonReadContext ctx, JsonEvent leaf, Class<?> target) {
        return switch (leaf) {
            case JsonEvent.StringValue string -> fromString(ctx, string.value(), target);
            case JsonEvent.BooleanValue bool -> {
                if (target != boolean.class && target != Boolean.class && target != Object.class) {
                    yield mismatch(ctx, "a boolean", target);
                }
                yield bool.value();
            }
            case JsonEvent.NumberValue number -> fromNumber(ctx, number.literal(), target);
            default -> {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                        "a %s cannot be read into %s".formatted(describe(leaf), target.getSimpleName()),
                        target.getSimpleName(), describe(leaf));
                yield null;
            }
        };
    }

    private static Object fromString(JsonReadContext ctx, String value, Class<?> target) {
        if (target == String.class || target == CharSequence.class || target == Object.class) {
            return value;
        }
        if (target == char.class || target == Character.class) {
            if (value.length() != 1) {
                ctx.report(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                        "a char takes a one-character string, not %d".formatted(value.length()),
                        "one character", value.length() + " characters");
                return null;
            }
            return value.charAt(0);
        }
        Optional<AtomType<?>> family = HostAtoms.forStringContentHostType(target);
        if (family.isPresent()) {
            return content(ctx, family.get(), value, target);
        }
        return mismatch(ctx, "a string", target);
    }

    /**
     * §5.1's contract boundary: the family parses, and this only carries a refusal across.
     *
     * <p>A rejection reports the atom's own {@code expected} -- the constraint that failed, from
     * {@link AtomTypeException}'s six-shape vocabulary -- rather than the target's Java name, which the
     * message and the diagnostic's own path already carry.
     */
    private static Object content(JsonReadContext ctx, AtomType<?> family, String value, Class<?> target) {
        try {
            return family.read(value, target);
        } catch (AtomTypeException e) {
            ctx.report(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, e.getMessage(), e.expected(), value);
            return null;
        } catch (IllegalArgumentException e) {
            // The family produced its own host value and the target cannot hold it -- a class declaring a
            // component the vocabulary does not read to, which is a bind problem and not a verdict.
            ctx.report(Diagnostic.Code.BIND_MISMATCH,
                    "cannot bind '%s' to %s".formatted(value, target.getSimpleName()), target.getSimpleName(), value);
            return null;
        }
    }

    private static Object fromNumber(JsonReadContext ctx, String literal, Class<?> target) {
        // Every narrowing runs off one BigDecimal, so `2147483648` at an int and `1.5` at an int fail the
        // same way: the digits are what arrived and the target is what cannot hold them (§3.1).
        if (target == double.class || target == Double.class) {
            return Double.parseDouble(literal);
        }
        if (target == float.class || target == Float.class) {
            return Float.parseFloat(literal);
        }
        BigDecimal decimal = new BigDecimal(literal);
        if (target == BigDecimal.class) {
            return decimal;
        }
        if (target == Object.class) {
            return decimal;
        }
        try {
            if (target == int.class || target == Integer.class) {
                return decimal.intValueExact();
            }
            if (target == long.class || target == Long.class) {
                return decimal.longValueExact();
            }
            if (target == short.class || target == Short.class) {
                return decimal.shortValueExact();
            }
            if (target == byte.class || target == Byte.class) {
                return decimal.byteValueExact();
            }
            if (target == BigInteger.class) {
                return decimal.toBigIntegerExact();
            }
        } catch (ArithmeticException e) {
            ctx.report(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, "%s is not exactly representable as %s"
                    .formatted(literal, target.getSimpleName()),
                    "a value that fits " + target.getSimpleName(), literal);
            return null;
        }
        return mismatch(ctx, "a number", target);
    }

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
