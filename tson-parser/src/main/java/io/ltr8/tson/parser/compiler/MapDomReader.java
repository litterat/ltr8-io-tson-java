package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.TsonValueReader;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.MapValue;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DOM mode's own {@code map} reader -- reads a map-shaped value into a plain {@code Map<Object,
 * Object>}, one entry per source entry, in source order (a {@code LinkedHashMap}, matching {@code
 * TsonMapperReader.toMap}'s own "last value wins" behavior for a duplicate key -- an ordinary {@code
 * put} in iteration order needs nothing extra to get that for free).
 *
 * <p>Everything else -- resolving the key/value readers, unwrapping the incoming {@link DataValue},
 * size validation, rejecting an absent key -- lives on {@link MapAbstractReader}.
 */
public final class MapDomReader extends MapAbstractReader<Map<Object, Object>> {

    public MapDomReader(String name, MapBody body, ValueReaderResolver resolver) {
        super(name, body, resolver);
    }

    @Override
    public Map<Object, Object> read(DataValue value) {
        List<MapValue.MapEntry> entries = entries(value);
        Map<Object, Object> result = new LinkedHashMap<>();
        readInto(entries, result::put);
        return result;
    }

    /** Validates {@code typeDefinition} is map-shaped before ever constructing a {@link MapDomReader} for it -- no {@link io.ltr8.bind.DataBindContext} needed, since DOM mode targets no Java type. */
    public static final class Factory implements ValueReaderFactory {

        @Override
        public TsonValueReader<?> create(String name, TypeDefinition typeDefinition, ValueReaderResolver resolver) {
            if (!(typeDefinition.body() instanceof MapBody body)) {
                throw new IllegalArgumentException("'" + name + "' is not map-shaped: " + typeDefinition.body());
            }
            return new MapDomReader(name, body, resolver);
        }
    }
}
