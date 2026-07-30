package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
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
    public Boolean read(TsonReadContext ctx) {
        ctx = ctx.withSchemaPosition(schemaPosition);
        EventSkip.annotationsAndTypeRef(ctx);
        TsonEvent e = ctx.peek();
        if (!(e instanceof TokenEvent token)) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected a token for 'boolean', found " + e,
                    "a token", String.valueOf(e));
            EventSkip.coreValue(ctx);
            return null;
        }
        ctx.next();
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
