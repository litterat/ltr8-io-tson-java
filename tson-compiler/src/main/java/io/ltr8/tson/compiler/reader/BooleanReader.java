package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.util.Optional;

/**
 * Object-binding mode's own reading for meta-kernel's {@code boolean => !enum [true false]} -- the
 * one real enum instance whose members are meant to stand in for a genuine Java {@code Boolean}, not
 * raw member text. Every other enum instance is bound via the ordinary {@code EnumParser} (through
 * {@link AtomValueReader#ENUM_OBJECT_MODE}), reading the member token's own text as a {@code
 * String} -- exactly right for an arbitrary, user-defined enum label, and exactly wrong for {@code
 * boolean} specifically. DOM mode is untouched: {@link AtomValueReader#ENUM} keeps producing {@code
 * String} for {@code boolean} there too.
 */
final class BooleanReader implements TsonValueReader<Boolean> {

    private final Optional<SourcePosition> schemaPosition;

    BooleanReader(Optional<SourcePosition> schemaPosition) {
        this.schemaPosition = schemaPosition;
    }

    @Override
    public Boolean read(DataValue value, TsonReadContext ctx) {
        ctx = ctx.at(value).withSchemaPosition(schemaPosition);
        if (value == null) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected a token for 'boolean', found no value",
                    "a token", "no value");
            return null;
        }
        CoreValue core = value.coreValue();
        if (!(core instanceof TokenValue token)) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected a token for 'boolean', found " + core,
                    "a token", String.valueOf(core));
            return null;
        }
        return switch (token.text()) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                        "expected 'true' or 'false' for 'boolean', found '" + token.text() + "'",
                        "'true' or 'false'", token.text());
                yield null;
            }
        };
    }
}
