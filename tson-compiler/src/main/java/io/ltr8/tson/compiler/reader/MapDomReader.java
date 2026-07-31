package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DOM mode's own {@code map} reader -- reads a map-shaped value into a plain {@code Map<Object,
 * Object>}, one entry per source entry, in source order (a {@code LinkedHashMap}, matching {@code
 * TsonObjectReader.toMap}'s own "last value wins" behavior for a duplicate key -- an ordinary {@code
 * put} in stream order needs nothing extra to get that for free).
 *
 * <p>Everything else -- resolving the key/value readers, confirming a map shape, size validation,
 * rejecting an absent key -- lives on {@link MapAbstractReader}.
 */
final class MapDomReader extends MapAbstractReader<Map<Object, Object>> {

    public MapDomReader(String name, MapBody body, TsonValueReaderResolver resolver, Optional<SourcePosition> schemaPosition) {
        super(name, body, resolver, schemaPosition);
    }

    @Override
    public Map<Object, Object> read(TsonReadContext ctx) {
        ctx = ctx.withSchemaPosition(schemaPosition);
        Shape shape = expectMapShape(ctx);
        if (shape == Shape.MISMATCH) {
            return null;
        }
        Map<Object, Object> result = new LinkedHashMap<>();
        if (shape == Shape.ENTRIES) {
            readInto(ctx, result::put);
        }
        return result;
    }

    /** Validates {@code typeDefinition} is map-shaped before ever constructing a {@link MapDomReader} for it -- no {@link io.ltr8.bind.DataBindContext} needed, since DOM mode targets no Java type. */
    public static final class Factory implements ValueReaderFactory {

        @Override
        public TsonValueReader<?> create(String name, TypeDefinition typeDefinition, TsonValueReaderResolver resolver) {
            if (!(typeDefinition.body() instanceof MapBody body)) {
                throw new IllegalArgumentException("'" + name + "' is not map-shaped: " + typeDefinition.body());
            }
            return new MapDomReader(name, body, resolver, typeDefinition.position());
        }
    }
}
