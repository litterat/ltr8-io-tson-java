package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.tree.*;
import io.ltr8.tson.tree.TsonRecord;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.tree.TsonValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tree mode's {@code record} reader -- reads a record-shaped value into a {@link TsonRecord} (name → {@link
 * TsonValue}, in schema-field order), the counterpart to the old DOM reader's plain {@code Map}. Every
 * shared concern (the compiled field list, shape checking, precomputed default/fixed values, single-pass
 * field reading) lives on {@link RecordAbstractReader}; this class only assembles the node. A field a read
 * doesn't produce (a missing required field, whose diagnostic is already reported) is simply omitted -- a
 * subsequent {@code get} of it yields {@link TsonMissing}.
 */
final class RecordTreeReader extends RecordAbstractReader<TsonValue> {

    public RecordTreeReader(String name, RecordBody body, TsonTypeReaderResolver resolver,
                            Optional<SourcePosition> schemaPosition,
                            AnnotationTypes annotationTypes) {
        super(name, body, resolver, schemaPosition);
        this.annotationTypes = annotationTypes;
    }

    /** The governing schema's annotation vocabulary, used to resolve and check this value's own annotations (§6). */
    private final AnnotationTypes annotationTypes;

    public static final class Factory implements ValueReaderFactory {

        @Override
        public TsonTypeReader<?> create(String name, TypeDefinition typeDefinition, ValueReaderContext context) {
            TsonTypeReaderResolver resolver = context.readers();
            if (!(typeDefinition.body() instanceof RecordBody body)) {
                throw new IllegalArgumentException("'" + name + "' is not record-shaped: " + typeDefinition.body());
            }
            RecordTreeReader ownParser = new RecordTreeReader(name, body, resolver, typeDefinition.position(),
                    AnnotationTypes.of(context));
            if (typeDefinition.subtypes().isEmpty()) {
                return ownParser;
            }
            return new VariantSchemaReader(name, ownParser, typeDefinition.subtypes(), resolver,
                    AnnotationTypes.of(context));
        }
    }

    @Override
    public TsonValue read(TsonReadContext ctx) {
        ctx = ctx.withSchemaPosition(schemaPosition);
        List<TsonAnnotation> annotations = AnnotationCapture.annotations(ctx, annotationTypes);
        ShapeResult shapeResult = expectRecordShape(ctx);
        if (shapeResult.shape() == Shape.MISMATCH) {
            return null;
        }
        Map<String, TsonValue> result = new LinkedHashMap<>();
        FieldSink fieldSink = (schemaIndex, decoded) -> putField(result, schemaIndex, decoded);
        boolean[] seen = switch (shapeResult.shape()) {
            case FIELDS -> readFields(ctx, fieldSink);
            case EMPTY -> new boolean[fields.size()];
            case POSITIONAL -> readPositional(ctx, fieldSink);
            case MISMATCH -> throw new IllegalStateException("unreachable");
        };
        TsonReadContext anchoredCtx = ctx.withPosition(shapeResult.anchor());
        for (int i = 0; i < fields.size(); i++) {
            if (!seen[i]) {
                putField(result, i, valueForAbsentField(i, anchoredCtx));
            }
        }
        validateGroups(anchoredCtx, seen);
        return new TsonRecord(result, Optional.of(name), annotations);
    }

    /** Puts a decoded field value into {@code result} as a node, omitting a {@code null} (a missing field -- already reported). */
    private void putField(Map<String, TsonValue> result, int schemaIndex, Object decoded) {
        if (decoded != null) {
            result.put(fields.get(schemaIndex).schema().name(), (TsonValue) decoded);
        }
    }
}
