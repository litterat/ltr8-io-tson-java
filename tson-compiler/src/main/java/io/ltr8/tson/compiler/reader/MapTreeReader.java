package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.tree.MapNode;
import io.ltr8.tson.tree.NullNode;
import io.ltr8.tson.tree.TsonAnnotation;
import io.ltr8.tson.tree.TsonNode;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tree mode's {@code map} reader -- reads a map-shaped value into a {@link MapNode} whose keys are
 * themselves {@link TsonNode}s (TSON map keys can be typed, §2.6), the counterpart to the old DOM reader's
 * plain {@code Map}. Preserving the record-vs-map distinction (both would be a Java {@code Map} in DOM mode)
 * is one of the reasons the tree exists.
 */
final class MapTreeReader extends MapAbstractReader<TsonNode> {

    public MapTreeReader(String name, MapBody body, TsonTypeReaderResolver resolver,
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
            if (!(typeDefinition.body() instanceof MapBody body)) {
                throw new IllegalArgumentException("'" + name + "' is not map-shaped: " + typeDefinition.body());
            }
            return new MapTreeReader(name, body, resolver, typeDefinition.position(), AnnotationTypes.of(context));
        }
    }

    @Override
    public TsonNode read(TsonReadContext ctx) {
        ctx = ctx.withSchemaPosition(schemaPosition);
        List<TsonAnnotation> annotations = AnnotationCapture.annotations(ctx, annotationTypes);
        Shape shape = expectMapShape(ctx);
        if (shape == Shape.MISMATCH) {
            return null;
        }
        List<MapNode.Entry> entries = new ArrayList<>();
        if (shape == Shape.ENTRIES) {
            readInto(ctx, (key, value) -> entries.add(new MapNode.Entry(node(key), node(value))));
        }
        return new MapNode(entries, Optional.of(name), annotations);
    }

    private static TsonNode node(Object decoded) {
        return decoded == null ? NullNode.instance() : (TsonNode) decoded;
    }
}
