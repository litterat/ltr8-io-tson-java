package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.stream.EventSkip;

/**
 * The reader a value entry compiles to ({@code ValueEntry}, [TSON-SCHEMA] §4.1): {@code orders => !api { ... }} is a
 * value of {@code api} and no type, so nothing is read against it. A schema naming one at a type position is refused
 * when it links; what reaches this is a data type-ref naming the entry, {@code !orders { ... }}, which is the
 * document's mistake and reported as {@link OpenTemplateReader} reports an unapplied template's.
 */
public final class ValueEntryReader implements TsonTypeReader<Object> {

    private final String name;
    private final String type;
    private final SchemaLocation schemaLocation;

    /**
     * @param name the value entry
     * @param type the type it is a value of
     */
    public ValueEntryReader(String name, String type, SchemaLocation schemaLocation) {
        this.name = name;
        this.type = type;
        this.schemaLocation = schemaLocation;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        ctx.report(Diagnostic.Code.TYPE_MISMATCH, "'" + name + "' is a value of the type '" + type + "', not a "
                + "type, so nothing is read against it -- write '!" + type + "' for a value of that type",
                "a type, not a value", "!" + name);
        EventSkip.dataValue(ctx);
        return null;
    }
}
