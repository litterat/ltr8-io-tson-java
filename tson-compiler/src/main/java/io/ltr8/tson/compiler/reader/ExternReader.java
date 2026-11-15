package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.resolver.Externs;
import io.ltr8.tson.compiler.stream.EventSkip;
import io.ltr8.tson.schema.meta.Extern;

/**
 * The reader an extern compiles to ({@link Extern}, [TSON-SCHEMA] §5.13). A value at a position it types is read
 * against the foreign entry in the foreign schema's scope (§7.9), which this library does not yet do, so the read
 * reports the gap and skips the value, as {@link ErrorReader} does.
 */
public final class ExternReader implements TsonTypeReader<Object> {

    private final String name;
    private final Extern extern;
    private final SchemaLocation schemaLocation;

    public ExternReader(String name, Extern extern, SchemaLocation schemaLocation) {
        this.name = name;
        this.extern = extern;
        this.schemaLocation = schemaLocation;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        ctx.report(Diagnostic.Code.NOT_IMPLEMENTED, "'" + name + "' is " + Externs.describe(extern) + ", and reading "
                + "a value through an extern, in its own schema's scope, is not implemented yet ([TSON-SCHEMA] §7.9)",
                "a type this library can read", "");
        EventSkip.dataValue(ctx);
        return null;
    }
}
