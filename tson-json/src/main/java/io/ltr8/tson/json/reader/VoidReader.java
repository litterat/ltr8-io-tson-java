package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;

/**
 * A {@code void}-typed position: [TSON-JSON] §5.7. The atom whose sole value is absence, whose JSON spelling
 * is null (§7) -- so this accepts null and nothing else, and produces the absent sentinel.
 *
 * <p>It is one instance of §7's general rule rather than a concession to JSON: [TSON-SCHEMA] §7.3 admits
 * {@code _} and nothing else at {@code void}, and this is where JSON-shaped data meets that rule. The one
 * place a second spelling of absence would be cheapest to admit is exactly here, and it is refused here too.
 */
final class VoidReader implements JsonTypeReader<Object> {

    private final String name;
    private final JsonSchemaLocation schemaLocation;

    VoidReader(String name, JsonSchemaLocation schemaLocation) {
        this.name = name;
        this.schemaLocation = schemaLocation;
    }

    /** Null -- the absent sentinel, which is what a {@code void} position always produces when it conforms. */
    @Override
    public Object read(JsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        JsonEvent event = ctx.next();
        if (event instanceof JsonEvent.NullValue) {
            return null;
        }
        ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                "'%s' is void, whose only value is absence -- JSON null -- and this is %s"
                        .formatted(name, JsonAtoms.describe(event)),
                "null", JsonAtoms.describe(event));
        EventSkip.value(ctx, event);
        return null;
    }
}
