package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.stream.AbsentEvent;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.util.Optional;

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
 * <p><b>The unquoted token {@code null} is accepted as an equivalent spelling of {@code _}</b> and
 * normalized to absence, which is [TSON-SCHEMA] §7.3's one concession to {@code null} under a schema. It is
 * local to this contract and deliberately so: {@code void} has a single inhabitant, so no
 * absence-vs-value distinction is lost, and §7.3 is explicit that this "does not change {@code null}'s
 * meaning elsewhere". Every other position hands the token to its own declared atom, where {@code null} is
 * ordinary text. This is also what makes JSON-shaped data readable under a schema: a JSON {@code null} at a
 * {@code void} position is absence, and anywhere else it must satisfy the declared type. Anything else is
 * rejected outright.
 */
final class VoidReader implements TsonTypeReader<Object> {

    private final Optional<SourcePosition> schemaPosition;

    VoidReader(Optional<SourcePosition> schemaPosition) {
        this.schemaPosition = schemaPosition;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        ctx = ctx.withSchemaPosition(schemaPosition);
        EventSkip.annotationsAndTypeRef(ctx);
        TsonEvent e = ctx.peek();
        if (!(e instanceof AbsentEvent) && !isNullSpelling(e)) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected the absent sentinel '_' for void, found " + TypeRefCheck.describe(e),
                    "the absent sentinel '_'", TypeRefCheck.describe(e));
            EventSkip.coreValue(ctx);
            return null;
        }
        ctx.next();
        return null;
    }

    /**
     * §7.3's concession: the <em>unquoted</em> token {@code null}, and only that. A quoted {@code "null"} is
     * a string by [TSON-DATA] §4.4 and stays one here -- the concession is about the spelling of absence,
     * not about any token whose text happens to read that way.
     */
    private static boolean isNullSpelling(TsonEvent e) {
        return e instanceof TokenEvent token && token.form() == TokenForm.UNQUOTED && token.text().equals("null");
    }
}
