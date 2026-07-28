package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.TsonValueReader;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.ScopedValue;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * DOM mode's own {@code array} reader -- reads an array-shaped value into a plain {@code
 * List<Object>}, one entry per element, always in source order regardless of what {@code
 * unordered}/{@code unique_items} say about the schema's own intent. DOM's contract is a plain,
 * predictable {@code List}/{@code Map} shape throughout (see {@link RecordDomReader}'s own
 * unconditional {@code Map} output); {@link ArrayBindReader} is the one that assembles decoded
 * elements into whatever real Java array/collection type a bound field actually declares.
 *
 * <p>Everything else -- resolving the element reader, unwrapping the incoming {@link DataValue},
 * size/uniqueness/absent-element validation -- lives on {@link ArrayAbstractReader}.
 */
public final class ArrayDomReader extends ArrayAbstractReader<List<Object>> {

    public ArrayDomReader(String name, ArrayBody body, ValueReaderResolver resolver) {
        super(name, body, resolver);
    }

    @Override
    public List<Object> read(DataValue value) {
        List<ScopedValue> elements = elements(value);
        List<Object> result = new ArrayList<>(elements.size());
        readInto(elements, result::add);
        return result;
    }

    /** Validates {@code typeDefinition} is array-shaped before ever constructing an {@link ArrayDomReader} for it -- no {@link io.ltr8.bind.DataBindContext} needed, since DOM mode targets no Java type. */
    public static final class Factory implements ValueReaderFactory {

        @Override
        public TsonValueReader<?> create(String name, TypeDefinition typeDefinition, ValueReaderResolver resolver) {
            if (!(typeDefinition.body() instanceof ArrayBody body)) {
                throw new IllegalArgumentException("'" + name + "' is not array-shaped: " + typeDefinition.body());
            }
            return new ArrayDomReader(name, body, resolver);
        }
    }
}
