package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
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
 * array). A slot that is absent (the sentinel {@code _}/{@code null} at an OPTIONAL position) or failed to
 * read is kept as a {@link TsonAbsent} placeholder -- a failed slot's story is carried by its diagnostic,
 * not by the node standing in for it.
 */
final class TupleTreeReader extends TupleAbstractReader<TsonValue> {

    public TupleTreeReader(String name, TupleBody body, TsonTypeReaderResolver resolver,
                           SchemaLocation schemaLocation,
                            AnnotationTypes annotationTypes) {
        super(name, body, resolver, schemaLocation);
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
            return new TupleTreeReader(name, body, resolver, SchemaLocation.of(name, typeDefinition),
                    AnnotationTypes.of(context));
        }
    }

    @Override
    public TsonValue read(TsonReadContext ctx) {
        ctx = ctx.withSchemaLocation(schemaLocation);
        List<TsonAnnotation> annotations = AnnotationCapture.annotations(ctx, annotationTypes);
        if (!expectTupleStart(ctx)) {
            return null;
        }
        List<TsonValue> elements = new ArrayList<>();
        for (Object decoded : decode(ctx)) {
            elements.add(decoded == null ? TsonAbsent.instance() : (TsonValue) decoded);
        }
        return new TsonTuple(elements, Optional.of(name), annotations);
    }
}
