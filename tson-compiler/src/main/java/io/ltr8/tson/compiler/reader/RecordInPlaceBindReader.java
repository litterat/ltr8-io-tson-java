package io.ltr8.tson.compiler.reader;

import io.ltr8.bind.DataClassRecord;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.schema.meta.RecordBody;

/**
 * Bind mode's reader of a record written in place -- the record an application of an open record closes to at a type
 * slot ([TSON-SCHEMA] §5.10) -- before anything says which class it builds.
 *
 * <p><b>A record in place binds by the component that holds it, never by a name.</b> It has no entry, so no binding
 * map can key on it ([TSON-SCHEMA] §8.2), and a record has no natural host form to fall back on, as an array has its
 * {@code List}. So this reader builds nothing: a record field, an element, a map's value or a tuple's position holding
 * it rebuilds it against the record class its own component declares ({@link #boundTo}, through {@link BindTargets}),
 * and read anywhere else it raises the {@link MissingBindingException} an entry nothing binds raises.
 */
final class RecordInPlaceBindReader implements TsonTypeReader<Object> {

    private final String name;
    private final RecordBody body;
    private final ValueReaderContext context;

    RecordInPlaceBindReader(String name, RecordBody body, ValueReaderContext context) {
        this.name = name;
        this.body = body;
        this.context = context;
    }

    /** The record read into {@code target}, the record class the component holding it declares. */
    RecordBindReader boundTo(DataClassRecord target) {
        return new RecordBindReader(name, context.displayName(name, body), body, target, context.readers(), context,
                context.locationOf(name), AnnotationTypes.of(context));
    }

    @Override
    public Object read(TsonReadContext ctx) {
        throw new MissingBindingException("'" + name + "' is a record written in place, which no binding map can "
                + "name ([TSON-SCHEMA] §8.2): it binds to the record class the component holding it declares, and "
                + "nothing here declares one");
    }
}
