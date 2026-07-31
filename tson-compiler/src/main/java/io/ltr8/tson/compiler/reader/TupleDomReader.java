package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * DOM mode's own {@code tuple} reader -- reads a tuple's own array-shaped value into a plain {@code
 * List<Object>}, one entry per position, in position order. {@code Arrays.asList} over the decoded
 * {@code Object[]} rather than a freshly-built {@code ArrayList} -- unlike {@link ArrayDomReader},
 * a tuple's own decoded values already land in a fixed-size array (see {@link
 * TupleAbstractReader#decode}), so wrapping it directly avoids a second allocation; {@code
 * Arrays.asList} tolerates {@code null} elements fine (an {@code OPTIONAL} position left absent),
 * unlike {@code List.of}.
 *
 * <p>Everything else -- resolving each position's own reader, confirming a tuple shape, arity
 * checking, absent-position handling -- lives on {@link TupleAbstractReader}.
 */
final class TupleDomReader extends TupleAbstractReader<List<Object>> {

    public TupleDomReader(String name, TupleBody body, TsonValueReaderResolver resolver, Optional<SourcePosition> schemaPosition) {
        super(name, body, resolver, schemaPosition);
    }

    @Override
    public List<Object> read(TsonReadContext ctx) {
        ctx = ctx.withSchemaPosition(schemaPosition);
        if (!expectTupleStart(ctx)) {
            return null;
        }
        return Arrays.asList(decode(ctx));
    }

    /** Validates {@code typeDefinition} is tuple-shaped before ever constructing a {@link TupleDomReader} for it -- no {@link io.ltr8.bind.DataBindContext} needed, since DOM mode targets no Java type. */
    public static final class Factory implements ValueReaderFactory {

        @Override
        public TsonValueReader<?> create(String name, TypeDefinition typeDefinition, TsonValueReaderResolver resolver) {
            if (!(typeDefinition.body() instanceof TupleBody body)) {
                throw new IllegalArgumentException("'" + name + "' is not tuple-shaped: " + typeDefinition.body());
            }
            return new TupleDomReader(name, body, resolver, typeDefinition.position());
        }
    }
}
