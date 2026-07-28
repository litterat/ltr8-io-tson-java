package io.ltr8.tson.parser.compiler;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassTuple;
import io.ltr8.tson.parser.TsonValueReader;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.ScopedValue;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.List;

/**
 * Object-binding mode's own {@code tuple} reader -- reads a tuple's own array-shaped value into a
 * real, bound Java object via {@code descriptor}, a {@code tson-bind} {@link DataClassTuple} already
 * resolved for this tuple's own target Java type (same division of responsibility as {@link
 * RecordBindReader}'s {@code DataClassRecord}).
 *
 * <p>Unlike {@link DataClassTuple}'s own read-side use (extracting values back out of an existing
 * tuple, one {@link io.ltr8.bind.DataClassElement#accessor()} at a time), building one is a single
 * call: {@code descriptor.constructor().invoke(values)}, the same all-at-once shape {@link
 * RecordBindReader} builds a record through -- {@link DataClassTuple} has no {@code put()}/iterator
 * at all (see its own Javadoc: a tuple is always built whole, never filled in one slot at a time).
 * As with {@link ArrayBindReader}/{@link MapBindReader}, there's no per-position narrowing here --
 * each position's own binding already happened recursively, inside whatever reader {@code resolver}
 * produced for its type.
 *
 * <p>Everything else -- resolving each position's own reader, unwrapping the incoming {@link
 * DataValue} and checking its arity, absent-position handling -- lives on {@link
 * TupleAbstractReader}.
 */
public final class TupleBindReader extends TupleAbstractReader<Object> {

    private final DataClassTuple descriptor;

    public TupleBindReader(String name, TupleBody body, DataClassTuple descriptor, ValueReaderResolver resolver) {
        super(name, body, resolver);
        this.descriptor = descriptor;
    }

    @Override
    public Object read(DataValue value) {
        List<ScopedValue> elements = elements(value);
        Object[] decoded = decode(elements);
        try {
            return descriptor.constructor().invoke(decoded);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("failed to construct " + descriptor.typeClass() + " from '" + name
                    + "'s own decoded elements", t);
        }
    }

    /** Validates both halves of what {@link #TupleBindReader} needs before ever constructing one -- {@code typeDefinition} is tuple-shaped, and {@code context} resolves {@code name} to a real, tuple-shaped {@link DataClassTuple} -- matching {@link RecordBindReader.Factory}'s own reasoning. */
    public static final class Factory implements ValueReaderFactory {

        private final DataBindContext context;

        public Factory(DataBindContext context) {
            this.context = context;
        }

        @Override
        public TsonValueReader<?> create(String name, TypeDefinition typeDefinition, ValueReaderResolver resolver) {
            if (!(typeDefinition.body() instanceof TupleBody body)) {
                throw new IllegalArgumentException("'" + name + "' is not tuple-shaped: " + typeDefinition.body());
            }
            DataClass dataClass = descriptorFor(name);
            if (!(dataClass instanceof DataClassTuple descriptor)) {
                throw new IllegalArgumentException("'" + name + "' resolves to " + dataClass.typeClass()
                        + ", which isn't tuple-shaped -- can't bind '" + name + "' as one");
            }
            return new TupleBindReader(name, body, descriptor, resolver);
        }

        private DataClass descriptorFor(String name) {
            try {
                return context.getDescriptor(name);
            } catch (DataBindException e) {
                throw new IllegalStateException("no bound Java class for '" + name + "'", e);
            }
        }
    }
}
