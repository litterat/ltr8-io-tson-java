package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DOM mode's own {@code record} reader -- reads a record-shaped value into a plain {@code
 * Map<String, Object>}, one entry per schema field. {@code resolver} turns a field's own type-ref
 * name into the {@link TsonValueReader} that reads its value.
 *
 * <p>Every {@code REQUIRED_FIXED}/{@code OPTIONAL_FIXED} field's own precomputed value ({@link
 * RecordAbstractReader#precomputedValue}) is written into {@code result} up front, before the data
 * is ever consulted -- {@link RecordAbstractReader#readFields} already discards any data occurrence
 * of one of these unread, so nothing later ever overwrites it. A single forward pass over the
 * stream (via {@link RecordAbstractReader#readFields}/{@link RecordAbstractReader#readPositional})
 * then fills every other field it actually finds -- {@code sink} is a plain {@code result::put} by
 * field name -- with a second pass over the full field list filling in whatever the {@code
 * boolean[]} it returns says wasn't seen.
 *
 * <p>Everything shared with {@link RecordBindReader} -- the compiled field list, the name lookup,
 * confirming a record-shaped value, precomputing default/fixed values -- lives on {@link
 * RecordAbstractReader}; this class holds only what's genuinely different about producing a plain
 * {@code Map} instead of a bound object.
 */
final class RecordDomReader extends RecordAbstractReader<Map<String, Object>> {

    public RecordDomReader(String name, RecordBody body, TsonValueReaderResolver resolver,
                            Optional<SourcePosition> schemaPosition) {
        super(name, body, resolver, schemaPosition);
    }

    /**
     * Validates {@code typeDefinition} is record-shaped before ever constructing a {@link
     * RecordDomReader} for it -- no {@link io.ltr8.bind.DataBindContext} needed, since DOM mode
     * targets no Java type. When {@code typeDefinition.subtypes()} is non-empty, wraps the result in
     * a {@link VariantSchemaReader} instead of returning it directly -- {@code ownParser} is always
     * reachable here (DOM mode has nothing resembling {@link RecordBindReader.Factory}'s "pure
     * marker interface" case, since an empty record body reads to a perfectly ordinary empty {@code
     * Map}), so this is the simpler of the two record factories' own subtype handling.
     */
    public static final class Factory implements ValueReaderFactory {

        @Override
        public TsonValueReader<?> create(String name, TypeDefinition typeDefinition, TsonValueReaderResolver resolver) {
            if (!(typeDefinition.body() instanceof RecordBody body)) {
                throw new IllegalArgumentException(
                        "'" + name + "' is not record-shaped: " + typeDefinition.body());
            }
            RecordDomReader ownParser = new RecordDomReader(name, body, resolver, typeDefinition.position());
            if (typeDefinition.subtypes().isEmpty()) {
                return ownParser;
            }
            return new VariantSchemaReader(name, ownParser, typeDefinition.subtypes(), resolver);
        }
    }

    @Override
    public Map<String, Object> read(TsonReadContext ctx) {
        ctx = ctx.withSchemaPosition(schemaPosition);
        ShapeResult shapeResult = expectRecordShape(ctx);
        if (shapeResult.shape() == Shape.MISMATCH) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int schemaIndex : fixedFieldIndices) {
            result.put(fields.get(schemaIndex).schema().name(), precomputedValue[schemaIndex]);
        }
        FieldSink sink = (schemaIndex, decoded) -> result.put(fields.get(schemaIndex).schema().name(), decoded);
        boolean[] seen = switch (shapeResult.shape()) {
            case FIELDS -> readFields(ctx, sink);
            case EMPTY -> new boolean[fields.size()];
            case POSITIONAL -> readPositional(ctx, sink);
            case MISMATCH -> throw new IllegalStateException("unreachable");
        };
        TsonReadContext anchoredCtx = ctx.withPosition(shapeResult.anchor());
        for (int i = 0; i < fields.size(); i++) {
            if (isFixed(fields.get(i).schema().state()) || seen[i]) {
                continue;
            }
            result.put(fields.get(i).schema().name(), defaultOrRequireNonFixed(i, anchoredCtx));
        }
        validateGroups(anchoredCtx, seen);
        return result;
    }
}
