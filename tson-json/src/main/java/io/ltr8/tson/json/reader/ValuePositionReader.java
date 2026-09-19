package io.ltr8.tson.json.reader;

import io.ltr8.tson.atom.AtomRefusal;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.atom.HostAtoms;
import io.ltr8.tson.atom.number.NumberNarrowing;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * The kernel's {@code value} escape hatch: [TSON-JSON] §5.7. It admits a boolean, an integer, a float or a
 * string ([TSON-SCHEMA] §4.2), and this encoding reads one by its own rule rather than through [TSON-DATA]
 * §4's base type resolution -- which §4.1 gives no role here, JSON's own grammar having already classified
 * the value.
 *
 * <p>So the four cases are read straight off the kind: booleans are booleans; a number with no fraction or
 * exponent is an integer and one with either is a float; a string is a string with its content uninspected,
 * so {@code "null"} is the four-character string and no quoting dance arises. The host types are the ones
 * the TSON side's own {@code value} produces, which is what lets one schema give one answer over both
 * encodings.
 *
 * <p>An object or array here is a validation error -- {@code value} admits scalars -- and so is JSON null at
 * this REQUIRED position, §7 having spent it as the absent sentinel. A {@code value} position is a single
 * token and not a scope, so {@code $schema} at one is a resolver error ([TSON-SCHEMA] §7.8); nothing here
 * admits an object at all, which is that rule already met.
 *
 * <p><b>Bound to a component, the component's host type says what the value was</b> ({@link #boundTo}). The four
 * cases are how the value is decoded, not what it means: {@code min: "PT30M"} on {@code duration_type} is the
 * string {@code PT30M}, and only the component holding a {@code Duration} says it was a duration. A value the
 * component already holds is returned untouched; a number a narrowing reaches is narrowed; anything else is read
 * again under the built-in atom that produces the component's class ({@link HostAtoms}), and gets that atom's
 * verdict. A class no built-in produces receives the natural value, and its own constructor decides. The same
 * rule the TSON reader's {@code value} applies at a bound slot.
 */
final class ValuePositionReader implements JsonTypeReader<Object> {

    private static final String EXPECTED = "a JSON boolean, number, or string";

    private final String name;
    private final JsonSchemaLocation schemaLocation;

    /** The host type the value is read as, or null for the natural reading. */
    private final Class<?> target;

    ValuePositionReader(String name, JsonSchemaLocation schemaLocation) {
        this(name, schemaLocation, null);
    }

    private ValuePositionReader(String name, JsonSchemaLocation schemaLocation, Class<?> target) {
        this.name = name;
        this.schemaLocation = schemaLocation;
        this.target = target;
    }

    /** This position read as {@code target} holds it: a bound component's wire class. */
    ValuePositionReader boundTo(Class<?> target) {
        return target == Object.class ? this : new ValuePositionReader(name, schemaLocation, target);
    }

    @Override
    public Object read(JsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        JsonEvent event = ctx.next();
        switch (event) {
            case JsonEvent.BooleanValue bool -> {
                return at(ctx, bool.value(), String.valueOf(bool.value()));
            }
            case JsonEvent.StringValue string -> {
                return at(ctx, string.value(), string.value());
            }
            case JsonEvent.NumberValue number -> {
                return at(ctx, integral(number.literal())
                        ? new BigInteger(number.literal())
                        : new BigDecimal(number.literal()), number.literal());
            }
            default -> {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH, "'%s' is a value, which takes %s, and this is %s"
                        .formatted(name, EXPECTED, JsonAtoms.describe(event)), EXPECTED, JsonAtoms.describe(event));
                EventSkip.value(ctx, event);
                return null;
            }
        }
    }

    /** {@code natural} as {@link #target} holds it, or null where the atom producing that class refused it. */
    private Object at(JsonReadContext ctx, Object natural, String content) {
        if (target == null || AtomType.wrap(target).isInstance(natural)) {
            return natural;
        }
        Object narrowed = narrowed(natural);
        if (narrowed != null) {
            return narrowed;
        }
        AtomType<?> atom = HostAtoms.forHostType(target).orElse(null);
        if (atom == null) {
            return natural;
        }
        try {
            return atom.read(content);
        } catch (AtomTypeException e) {
            AtomRefusal refusal = AtomRefusal.of(e, content, target).named(name);
            ctx.report(refusal.code(), refusal.message(), refusal.expected(), refusal.actual());
            return null;
        }
    }

    /**
     * A number as {@link #target} holds it, or null where no numeric narrowing reaches it -- the signal to try
     * the atom producing that class, not a refusal.
     */
    private Object narrowed(Object natural) {
        try {
            Object narrowed = switch (natural) {
                case BigInteger integer -> NumberNarrowing.narrowIntegral(integer, target);
                case BigDecimal decimal -> NumberNarrowing.narrowDecimal(decimal, target);
                default -> null;
            };
            return narrowed != null && AtomType.wrap(target).isInstance(narrowed) ? narrowed : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** §5.7's split, read off the literal: a fraction or an exponent makes it a float, and nothing else does. */
    private static boolean integral(String literal) {
        return literal.indexOf('.') < 0 && literal.indexOf('e') < 0 && literal.indexOf('E') < 0;
    }
}
