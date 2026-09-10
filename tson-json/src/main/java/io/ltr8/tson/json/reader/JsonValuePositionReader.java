package io.ltr8.tson.json.reader;

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
 */
final class JsonValuePositionReader implements JsonTypeReader<Object> {

    private static final String EXPECTED = "a JSON boolean, number, or string";

    private final String name;
    private final JsonSchemaLocation schemaLocation;

    JsonValuePositionReader(String name, JsonSchemaLocation schemaLocation) {
        this.name = name;
        this.schemaLocation = schemaLocation;
    }

    @Override
    public Object read(JsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        JsonEvent event = ctx.next();
        switch (event) {
            case JsonEvent.BooleanValue bool -> {
                return bool.value();
            }
            case JsonEvent.StringValue string -> {
                return string.value();
            }
            case JsonEvent.NumberValue number -> {
                return integral(number.literal())
                        ? new BigInteger(number.literal())
                        : new BigDecimal(number.literal());
            }
            default -> {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH, "'%s' is a value, which takes %s, and this is %s"
                        .formatted(name, EXPECTED, JsonAtoms.describe(event)), EXPECTED, JsonAtoms.describe(event));
                JsonEventSkip.value(ctx, event);
                return null;
            }
        }
    }

    /** §5.7's split, read off the literal: a fraction or an exponent makes it a float, and nothing else does. */
    private static boolean integral(String literal) {
        return literal.indexOf('.') < 0 && literal.indexOf('e') < 0 && literal.indexOf('E') < 0;
    }
}
