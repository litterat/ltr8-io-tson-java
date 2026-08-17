package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.tree.*;
import io.ltr8.tson.tree.TsonAbsent;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.tree.TsonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tree mode's {@code array} reader -- reads an array-shaped value into a {@link TsonArray}, one {@link
 * TsonValue} per element in source order, the counterpart to the old DOM reader's plain {@code List}.
 * Distinct from {@link TupleTreeReader}, which reads a fixed-arity, positionally-typed sequence into a {@code
 * TsonTuple}. A failed/mismatched element is kept as a {@link TsonAbsent} placeholder (its diagnostic is
 * already reported) so later elements' indices stay accurate.
 */
final class ArrayTreeReader extends ArrayAbstractReader<TsonValue> {

    public ArrayTreeReader(String name, ArrayBody body, TsonTypeReaderResolver resolver,
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
            if (!(typeDefinition.body() instanceof ArrayBody body)) {
                throw new IllegalArgumentException("'" + name + "' is not array-shaped: " + typeDefinition.body());
            }
            return new ArrayTreeReader(name, body, resolver, typeDefinition.position(), AnnotationTypes.of(context));
        }
    }

    @Override
    public TsonValue read(TsonReadContext ctx) {
        ctx = ctx.withSchemaPosition(schemaPosition);
        List<TsonAnnotation> annotations = AnnotationCapture.annotations(ctx, annotationTypes);
        if (!expectArrayStart(ctx)) {
            return null;
        }
        List<TsonValue> elements = new ArrayList<>();
        readInto(ctx, decoded -> elements.add(decoded == null ? TsonAbsent.instance() : (TsonValue) decoded));
        return new TsonArray(elements, Optional.of(name), annotations);
    }
}
