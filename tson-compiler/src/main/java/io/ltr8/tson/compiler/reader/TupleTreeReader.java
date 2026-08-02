package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.compiler.tree.NullNode;
import io.ltr8.tson.compiler.tree.TsonNode;
import io.ltr8.tson.compiler.tree.TupleNode;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tree mode's {@code tuple} reader -- reads a fixed-arity, positionally-typed sequence into a {@link
 * TupleNode}, the counterpart to {@link TupleDomReader}'s plain {@code List} and a distinct kind from {@link
 * ArrayTreeReader} (a schemaless read, which has no schema to tell tuple from array, can only produce an
 * array). A failed/out-of-arity slot is kept as a {@link NullNode} placeholder.
 */
final class TupleTreeReader extends TupleAbstractReader<TsonNode> {

    public TupleTreeReader(String name, TupleBody body, TsonValueReaderResolver resolver,
                           Optional<SourcePosition> schemaPosition) {
        super(name, body, resolver, schemaPosition);
    }

    public static final class Factory implements ValueReaderFactory {

        @Override
        public TsonValueReader<?> create(String name, TypeDefinition typeDefinition, TsonValueReaderResolver resolver) {
            if (!(typeDefinition.body() instanceof TupleBody body)) {
                throw new IllegalArgumentException("'" + name + "' is not tuple-shaped: " + typeDefinition.body());
            }
            return new TupleTreeReader(name, body, resolver, typeDefinition.position());
        }
    }

    @Override
    public TsonNode read(TsonReadContext ctx) {
        ctx = ctx.withSchemaPosition(schemaPosition);
        if (!expectTupleStart(ctx)) {
            return null;
        }
        List<TsonNode> elements = new ArrayList<>();
        for (Object decoded : decode(ctx)) {
            elements.add(decoded == null ? NullNode.instance() : (TsonNode) decoded);
        }
        return new TupleNode(elements, Optional.of(name), List.of());
    }
}
