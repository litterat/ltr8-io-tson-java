package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.ValueParser;
import io.ltr8.tson.compiler.stream.EventSkip;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.schema.meta.ApplyBody;

/**
 * The reader of a record field typed by an application written inline ({@code r: meta.ref<order>}, {@code b:
 * box<text>}) in a compile of a resolution in progress ({@code TsonSchemaCompiler.compileReady}). Such an application
 * closes in place once every declaration is ready ([TSON-SCHEMA] §5.10), the field then holding the type written in
 * place, so only a compile of the entries ready so far ({@code SchemaResolver.readySoFar}) meets the field still
 * typed by the application. Its head is a template, which no value is read against, so the head's reader is never
 * the field's.
 *
 * <ul>
 *   <li><b>A held value</b>, the field's default or pin decoded once every reader exists, binds as written, as at a
 *       declaration not yet ready ({@link PendingTypeReader}): what base type resolution gives the token ([TSON-DATA]
 *       §4). The value is judged against the closed type when the whole schema links.</li>
 *   <li><b>A value a schema document writes</b> at the field (a value entry's payload) is this library's gap,
 *       reported as one: the application does not yet wait in the order as a named one does.</li>
 * </ul>
 */
record UnclosedApplicationReader(String field, ApplyBody application) implements TsonTypeReader<Object> {

    @Override
    public Object read(TsonReadContext ctx) {
        if (ctx.readsSchemaDocument()) {
            String found = TypeTagCheck.describe(ctx.peek());
            ctx.report(Diagnostic.Code.NOT_IMPLEMENTED, "field '" + field + "' is typed by the application '"
                    + application.text() + "' written inline, which closes in place only once every declaration "
                    + "is ready, and this library does not yet read a value entry against it before then -- declare "
                    + "the application by name ('my_type => " + application.text() + "') and type the field by that "
                    + "name", "a position this library can read", found);
            EventSkip.annotationsAndTypeRef(ctx);
            EventSkip.coreValue(ctx);
            return null;
        }
        TsonEvent event = ctx.next();
        if (!(event instanceof TokenEvent token)) {
            throw new IllegalStateException("field '" + field + "': a held value is a bare token, found " + event);
        }
        return ValueParser.INSTANCE.read(new TokenValue(token.text(), token.form()));
    }
}
