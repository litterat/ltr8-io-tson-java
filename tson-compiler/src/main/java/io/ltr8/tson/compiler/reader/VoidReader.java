package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.stream.AbsentEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;


/**
 * Parses meta-kernel's {@code void} instance of the {@code unit} atom constructor -- per its own
 * kernel doc, "parsing contract admits only the absent sentinel {@code _}. The host value is
 * absent." That contract can't be expressed as an {@code io.ltr8.tson.compiler.atom.AtomType<T>} at
 * all ({@code AtomType.read(TokenValue)} only ever sees a token, and {@code _} isn't one), so this
 * reads the {@link DataValue} directly rather than going through {@link AtomTypeReader}.
 *
 * <p>Reads to plain Java {@code null} -- "the host value is absent" has no more specific natural
 * representation.
 *
 * <p><b>{@code _} is the only spelling accepted</b>, the unquoted token {@code null} included: absence has
 * one spelling in the notation, so there is no second one for this contract to admit. The token {@code null}
 * is ordinary text here as it is at every other position, and fails this reader the way {@code frobnicate}
 * does. A JSON document's {@code null} reaches absence through a JSON reader, which maps it in the model,
 * where the position's own state decides whether absence is admitted at all.
 */
final class VoidReader implements TsonTypeReader<Object> {

    private final SchemaLocation schemaLocation;

    VoidReader(SchemaLocation schemaLocation) {
        this.schemaLocation = schemaLocation;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        EventSkip.annotationsAndTypeRef(ctx);
        TsonEvent e = ctx.peek();
        if (!(e instanceof AbsentEvent)) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected the absent sentinel '_' for void, found " + TypeRefCheck.describe(e),
                    "the absent sentinel '_'", TypeRefCheck.describe(e));
            EventSkip.coreValue(ctx);
            return null;
        }
        ctx.next();
        return null;
    }
}
