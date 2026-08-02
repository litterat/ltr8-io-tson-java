package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.compiler.tree.RecordNode;
import io.ltr8.tson.compiler.tree.TsonNode;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tree mode's {@code record} reader -- reads a record-shaped value into a {@link RecordNode} (name → {@link
 * TsonNode}, in schema-field order), the counterpart to the old DOM reader's plain {@code Map}. Every
 * shared concern (the compiled field list, shape checking, precomputed default/fixed values, single-pass
 * field reading) lives on {@link RecordAbstractReader}; this class only assembles the node. A field a read
 * doesn't produce (a missing required field, whose diagnostic is already reported) is simply omitted -- a
 * subsequent {@code get} of it yields {@link io.ltr8.tson.compiler.tree.MissingNode}.
 */
final class RecordTreeReader extends RecordAbstractReader<TsonNode> {

    public RecordTreeReader(String name, RecordBody body, TsonValueReaderResolver resolver,
                            Optional<SourcePosition> schemaPosition) {
        super(name, body, resolver, schemaPosition);
    }

    public static final class Factory implements ValueReaderFactory {

        @Override
        public TsonValueReader<?> create(String name, TypeDefinition typeDefinition, TsonValueReaderResolver resolver) {
            if (!(typeDefinition.body() instanceof RecordBody body)) {
                throw new IllegalArgumentException("'" + name + "' is not record-shaped: " + typeDefinition.body());
            }
            RecordTreeReader ownParser = new RecordTreeReader(name, body, resolver, typeDefinition.position());
            if (typeDefinition.subtypes().isEmpty()) {
                return ownParser;
            }
            return new VariantSchemaReader(name, ownParser, typeDefinition.subtypes(), resolver);
        }
    }

    @Override
    public TsonNode read(TsonReadContext ctx) {
        ctx = ctx.withSchemaPosition(schemaPosition);
        ShapeResult shapeResult = expectRecordShape(ctx);
        if (shapeResult.shape() == Shape.MISMATCH) {
            return null;
        }
        Map<String, TsonNode> result = new LinkedHashMap<>();
        for (int schemaIndex : fixedFieldIndices) {
            putField(result, schemaIndex, precomputedValue[schemaIndex]);
        }
        FieldSink fieldSink = (schemaIndex, decoded) -> putField(result, schemaIndex, decoded);
        boolean[] seen = switch (shapeResult.shape()) {
            case FIELDS -> readFields(ctx, fieldSink);
            case EMPTY -> new boolean[fields.size()];
            case POSITIONAL -> readPositional(ctx, fieldSink);
            case MISMATCH -> throw new IllegalStateException("unreachable");
        };
        TsonReadContext anchoredCtx = ctx.withPosition(shapeResult.anchor());
        for (int i = 0; i < fields.size(); i++) {
            if (isFixed(fields.get(i).schema().state()) || seen[i]) {
                continue;
            }
            putField(result, i, defaultOrRequireNonFixed(i, anchoredCtx));
        }
        validateGroups(anchoredCtx, seen);
        return new RecordNode(result, Optional.of(name), List.of());
    }

    /** Puts a decoded field value into {@code result} as a node, omitting a {@code null} (a missing field -- already reported). */
    private void putField(Map<String, TsonNode> result, int schemaIndex, Object decoded) {
        if (decoded != null) {
            result.put(fields.get(schemaIndex).schema().name(), (TsonNode) decoded);
        }
    }
}
