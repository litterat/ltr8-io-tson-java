package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.tree.*;
import io.ltr8.tson.tree.TsonValue;
import io.ltr8.tson.tree.TsonTuple;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tree mode's {@code tuple} reader -- reads a fixed-arity, positionally-typed sequence into a {@link
 * TsonTuple}, the counterpart to the old DOM reader's plain {@code List} and a distinct kind from {@link
 * ArrayTreeReader} (a schemaless read, which has no schema to tell tuple from array, can only produce an
 * array). A slot written as the sentinel {@code _} at an OPTIONAL position is a {@link TsonAbsent}; a tuple
 * whose read reported anything is not built ({@link ConstructionGuard}).
 */
final class TupleTreeReader extends TupleAbstractReader<TsonValue> {

    public TupleTreeReader(String name, String displayName, TupleBody body, TsonTypeReaderResolver resolver,
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
            if (!(typeDefinition.body() instanceof TupleBody body)) {
                throw new IllegalArgumentException("'" + name + "' is not tuple-shaped: " + typeDefinition.body());
            }
            return new TupleTreeReader(name, EntryDisplayName.of(name, typeDefinition), body, resolver,
                    context.locationOf(name, typeDefinition),
                    AnnotationTypes.of(context));
        }
    }

    @Override
    public TsonValue read(TsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        List<TsonAnnotation> annotations = AnnotationCapture.annotations(ctx, annotationTypes);
        int mark = ConstructionGuard.mark(ctx);
        if (!expectTupleStart(ctx)) {
            return null;
        }
        List<TsonValue> elements = new ArrayList<>();
        for (Object decoded : decode(ctx)) {
            elements.add(decoded == null ? TsonAbsent.instance() : (TsonValue) decoded);
        }
        if (ConstructionGuard.abandoned(ctx, mark)) {
            return null;
        }
        return new TsonTuple(elements, Optional.of(name), annotations);
    }
}
