package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.tree.ArrayNode;
import io.ltr8.tson.tree.NullNode;
import io.ltr8.tson.tree.TsonNode;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tree mode's {@code array} reader -- reads an array-shaped value into an {@link ArrayNode}, one {@link
 * TsonNode} per element in source order, the counterpart to the old DOM reader's plain {@code List}.
 * Distinct from {@link TupleTreeReader}, which reads a fixed-arity, positionally-typed sequence into a {@code
 * TupleNode}. A failed/mismatched element is kept as a {@link NullNode} placeholder (its diagnostic is
 * already reported) so later elements' indices stay accurate.
 */
final class ArrayTreeReader extends ArrayAbstractReader<TsonNode> {

    public ArrayTreeReader(String name, ArrayBody body, TsonValueReaderResolver resolver,
                           Optional<SourcePosition> schemaPosition) {
        super(name, body, resolver, schemaPosition);
    }

    public static final class Factory implements ValueReaderFactory {

        @Override
        public TsonValueReader<?> create(String name, TypeDefinition typeDefinition, TsonValueReaderResolver resolver) {
            if (!(typeDefinition.body() instanceof ArrayBody body)) {
                throw new IllegalArgumentException("'" + name + "' is not array-shaped: " + typeDefinition.body());
            }
            return new ArrayTreeReader(name, body, resolver, typeDefinition.position());
        }
    }

    @Override
    public TsonNode read(TsonReadContext ctx) {
        ctx = ctx.withSchemaPosition(schemaPosition);
        if (!expectArrayStart(ctx)) {
            return null;
        }
        List<TsonNode> elements = new ArrayList<>();
        readInto(ctx, decoded -> elements.add(decoded == null ? NullNode.instance() : (TsonNode) decoded));
        return new ArrayNode(elements, Optional.of(name), List.of());
    }
}
