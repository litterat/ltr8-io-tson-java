package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.stream.EventSkip;
import io.ltr8.tson.schema.meta.IncompleteEntry;

/**
 * The reader an incomplete entry compiles to ({@link IncompleteEntry}, [TSON-SCHEMA] §5.5): it is no type, so nothing
 * is read against it. A schema naming one at a type position is refused when it links; what reaches this is a data
 * tag naming it, the document's mistake, reported as {@link ValueEntryReader} reports a value entry's.
 */
public final class IncompleteEntryReader implements TsonTypeReader<Object> {

    private final String name;
    private final IncompleteEntry incomplete;
    private final SchemaLocation schemaLocation;

    public IncompleteEntryReader(String name, IncompleteEntry incomplete, SchemaLocation schemaLocation) {
        this.name = name;
        this.incomplete = incomplete;
        this.schemaLocation = schemaLocation;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        ctx.report(Diagnostic.Code.TYPE_MISMATCH, "'" + name + "' is an incomplete '" + incomplete.constructor()
                + "' with " + incomplete.absent() + " absent, not a type, so nothing is read against it -- tag the "
                + "value with a type its schema declares by completing it", "a type, not an incomplete entry",
                "!" + name);
        EventSkip.dataValue(ctx);
        return null;
    }
}
