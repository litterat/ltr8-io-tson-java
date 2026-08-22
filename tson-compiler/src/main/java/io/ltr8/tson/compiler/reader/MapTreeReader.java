package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.tree.*;
import io.ltr8.tson.tree.TsonMap;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.tree.TsonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tree mode's {@code map} reader -- reads a map-shaped value into a {@link TsonMap} whose keys are
 * themselves {@link TsonValue}s (TSON map keys can be typed, §2.6), the counterpart to the old DOM reader's
 * plain {@code Map}. Preserving the record-vs-map distinction (both would be a Java {@code Map} in DOM mode)
 * is one of the reasons the tree exists.
 */
final class MapTreeReader extends MapAbstractReader<TsonValue> {

    public MapTreeReader(String name, String displayName, MapBody body, TsonTypeReaderResolver resolver,
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
            if (!(typeDefinition.body() instanceof MapBody body)) {
                throw new IllegalArgumentException("'" + name + "' is not map-shaped: " + typeDefinition.body());
            }
            return new MapTreeReader(name, EntryDisplayName.of(name, typeDefinition), body, resolver,
                    context.locationOf(name, typeDefinition),
                    AnnotationTypes.of(context));
        }
    }

    @Override
    public TsonValue read(TsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        List<TsonAnnotation> annotations = AnnotationCapture.annotations(ctx, annotationTypes);
        Shape shape = expectMapShape(ctx);
        if (shape == Shape.MISMATCH) {
            return null;
        }
        List<TsonMap.Entry> entries = new ArrayList<>();
        if (shape == Shape.ENTRIES) {
            readInto(ctx, (key, value) -> entries.add(new TsonMap.Entry(node(key), node(value))));
        }
        return new TsonMap(entries, Optional.of(name), annotations);
    }

    private static TsonValue node(Object decoded) {
        return decoded == null ? TsonAbsent.instance() : (TsonValue) decoded;
    }
}
