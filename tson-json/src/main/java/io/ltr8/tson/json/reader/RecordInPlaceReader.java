package io.ltr8.tson.json.reader;

import io.ltr8.bind.DataClassRecord;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.schema.meta.Top;

/**
 * Bind mode's reader of a record written in place -- the record an application of an open record closes to at a type
 * slot ([TSON-SCHEMA] §5.10) -- before anything says which class it builds.
 *
 * <p><b>A record in place binds by the component that holds it, never by a name.</b> It has no entry, so no binding
 * map can key on it ([TSON-SCHEMA] §8.2), and a record has no natural host form to fall back on, as an array has its
 * {@code List}. So this reader builds nothing: a component holding it -- a field's, an element's, a map value's or a
 * tuple position's -- reads it again for the record class it declares ({@link #boundTo}, through {@link BindTargets}),
 * and read anywhere else it raises the {@link MissingBindingException} an entry nothing binds raises. {@code
 * tson-compiler}'s {@code RecordInPlaceBindReader} is the same rule over that encoding's readers.
 */
final class RecordInPlaceReader implements JsonTypeReader<Object> {

    private final String name;
    private final Top definition;
    private final ValueReaderContext context;

    RecordInPlaceReader(String name, Top definition, ValueReaderContext context) {
        this.name = name;
        this.definition = definition;
        this.context = context;
    }

    /** The record read into {@code target}, the record class the component holding it declares. */
    JsonTypeReader<?> boundTo(DataClassRecord target) {
        return BindRecordBuilder.boundTo(name, definition, context, target);
    }

    @Override
    public Object read(JsonReadContext ctx) {
        throw new MissingBindingException("'" + name + "' is a record written in place, which no binding map can "
                + "name ([TSON-SCHEMA] §8.2): it binds to the record class the component holding it declares, and "
                + "nothing here declares one");
    }
}
