package io.ltr8.tson.json.bind;

import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassBridge;
import io.ltr8.tson.json.JsonPosition;
import io.ltr8.tson.json.stream.JsonEvent;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * One JSON leaf into one host value, at a {@link DataClassAtom} position.
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
final class JsonAtoms {

    private JsonAtoms() {
    }

    /** {@code leaf} at {@code atom}'s type, bridge applied. {@code null} only where the leaf was JSON null. */
    static Object bind(JsonEvent leaf, DataClassAtom atom, JsonPosition at) {
        Class<?> wire = atom.dataClass();
        Object value = leaf instanceof JsonEvent.NullValue ? null : bindTo(leaf, wire, at);
        DataClassBridge bridge = atom.bridge().orElse(null);
        if (bridge == null || value == null) {
            return value;
        }
        try {
            return bridge.toObject().invoke(value);
        } catch (Throwable e) {
            throw new JsonBindException("'%s' is not a %s: %s"
                    .formatted(text(leaf), atom.typeClass().getSimpleName(), e.getMessage()), at);
        }
    }

    private static Object bindTo(JsonEvent leaf, Class<?> target, JsonPosition at) {
        return switch (leaf) {
            case JsonEvent.StringValue string -> fromString(string.value(), target, at);
            case JsonEvent.BooleanValue bool -> {
                if (target != boolean.class && target != Boolean.class && target != Object.class) {
                    throw mismatch("a boolean", target, at);
                }
                yield bool.value();
            }
            case JsonEvent.NumberValue number -> fromNumber(number.literal(), target, at);
            default -> throw new JsonBindException(
                    "a %s cannot be read into %s".formatted(describe(leaf), target.getSimpleName()), at);
        };
    }

    private static Object fromString(String value, Class<?> target, JsonPosition at) {
        if (target == String.class || target == CharSequence.class || target == Object.class) {
            return value;
        }
        if (target == char.class || target == Character.class) {
            if (value.length() != 1) {
                throw new JsonBindException("a char takes a one-character string, not %d".formatted(value.length()), at);
            }
            return value.charAt(0);
        }
        throw mismatch("a string", target, at);
    }

    private static Object fromNumber(String literal, Class<?> target, JsonPosition at) {
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
            throw new JsonBindException("%s is not exactly representable as %s"
                    .formatted(literal, target.getSimpleName()), at);
        }
        throw mismatch("a number", target, at);
    }

    private static JsonBindException mismatch(String found, Class<?> target, JsonPosition at) {
        return new JsonBindException("%s cannot be read into %s".formatted(found, target.getSimpleName()), at);
    }

    /** How a leaf names itself in a message. */
    static String describe(JsonEvent event) {
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
