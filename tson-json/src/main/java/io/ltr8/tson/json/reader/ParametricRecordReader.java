package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;

import java.util.List;

/**
 * An entry that declares type parameters. No position names a parametric record bare ([TSON-SCHEMA] §5.10),
 * so naming one as a root type or reaching one from a position is an ordinary data diagnostic rather than a
 * fault -- a schema naming one unapplied was already refused at link time.
 *
 * <p>Built before the body is looked at at all, so a parametric record's unsubstituted body never reaches a factory.
 */
final class ParametricRecordReader implements JsonTypeReader<Object> {

    private final String name;
    private final List<String> parameters;
    private final JsonSchemaLocation schemaLocation;

    ParametricRecordReader(String name, List<String> parameters, JsonSchemaLocation schemaLocation) {
        this.name = name;
        this.parameters = List.copyOf(parameters);
        this.schemaLocation = schemaLocation;
    }

    @Override
    public Object read(JsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        ctx.report(Diagnostic.Code.TYPE_MISMATCH, message(), "a type, not a parametric record", name);
        EventSkip.nextValue(ctx);
        return null;
    }

    private String message() {
        return "'" + name + "' is a parametric record taking " + parameters.size() + " type argument"
                + (parameters.size() == 1 ? "" : "s") + " " + parameters
                + ", and no position names a parametric record bare (§5.10) -- name the application in the schema "
                + "('my_type => " + name + "<...>') and bind this document to 'my_type'";
    }
}
