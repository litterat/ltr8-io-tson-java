package io.ltr8.tson.json.atom;

import io.ltr8.bind.DataClassAtom;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.bind.DataClassBridge;
import io.ltr8.tson.json.reader.JsonReadContext;
import io.ltr8.tson.json.stream.JsonEvent;

import java.math.BigDecimal;
import java.math.BigInteger;

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
 * type on the wire and {@code typeClass()} the one the class wants -- so a {@code UUID} component is
 * read as the {@code String} its bridge declares and handed to {@code toObject}. Every atom
 * {@code TsonAtomContext.registerDefaults} registers is string-bridged, so this covers the temporal,
 * network and identifier families without naming one of them.
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
        return mismatch(ctx, "a string", target);
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
