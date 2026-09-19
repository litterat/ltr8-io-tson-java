package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.tree.*;
import io.ltr8.tson.tree.TsonAbsent;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.tree.TsonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tree mode's {@code array} reader -- reads an array-shaped value into a {@link TsonArray}, one {@link
 * TsonValue} per element in source order, the counterpart to the old DOM reader's plain {@code List}.
 * Distinct from {@link TupleTreeReader}, which reads a fixed-arity, positionally-typed sequence into a {@code
 * TsonTuple}. An element written {@code _} is a {@link TsonAbsent}; an array whose read reported anything is
 * not built ({@link ConstructionGuard}).
 */
final class ArrayTreeReader extends ArrayAbstractReader<TsonValue> {

    public ArrayTreeReader(String name, String displayName, ArrayBody body, TsonTypeReaderResolver resolver,
                           SchemaLocation schemaLocation,
                            AnnotationTypes annotationTypes) {
        super(name, displayName, body, resolver, schemaLocation);
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
            return new ArrayTreeReader(name, EntryDisplayName.of(name, typeDefinition), body, resolver,
                    context.locationOf(name, typeDefinition),
                    AnnotationTypes.of(context));
        }
    }

    @Override
    public TsonValue read(TsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        List<TsonAnnotation> annotations = AnnotationCapture.annotations(ctx, annotationTypes);
        int mark = ConstructionGuard.mark(ctx);
        if (!expectArrayStart(ctx)) {
            return null;
        }
        List<TsonValue> elements = new ArrayList<>();
        // A null element is a stated `_`: a refused one abandons the array below.
        readInto(ctx, decoded -> elements.add(decoded == null ? TsonAbsent.instance() : (TsonValue) decoded));
        if (ConstructionGuard.abandoned(ctx, mark)) {
            return null;
        }
        return new TsonArray(elements, Optional.of(name), annotations);
    }
}
