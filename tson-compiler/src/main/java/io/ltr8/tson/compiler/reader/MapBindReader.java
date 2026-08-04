package io.ltr8.tson.compiler.reader;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataParameterizedType;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;

/**
 * Object-binding mode's own {@code map} reader -- reads a map-shaped value into a real, bound Java
 * map via {@code descriptor}, a {@code tson-bind} {@link DataClassMap} already resolved for this
 * map's own target Java type (same division of responsibility as {@link RecordBindReader}'s {@code
 * DataClassRecord}/{@link ArrayBindReader}'s {@code DataClassArray}).
 *
 * <p>Mirrors {@code TsonObjectReader.toMap} exactly: {@code descriptor.constructor().invoke(size)}
 * to allocate the target with a known capacity, then {@code descriptor.put().invoke(mapData, key,
 * value)} per decoded entry -- no iterator needed, unlike {@link DataClassMap}'s own *reading* side,
 * since writing a map only ever needs {@code put}. Unlike {@link ArrayBindReader}, there's no fixed-
 * size-target concern here at all -- {@code tson-bind}'s own {@code MapAccessBridge.constructor()}
 * always resolves to a growable, hash-based constructor (confirmed by reading {@code
 * DefaultMapBinder} directly), so this always constructs empty ({@code invoke(0)}) and appends
 * incrementally, one entry at a time, with no buffer-then-allocate step. As with {@link
 * ArrayBindReader}, there's no narrowing at this level either -- each key and value's own binding
 * already happened recursively, inside whatever reader {@code resolver} produced for its type.
 *
 * <p>Everything else -- resolving the key/value readers, confirming a map shape, size validation,
 * rejecting an absent key -- lives on {@link MapAbstractReader}.
 */
final class MapBindReader extends MapAbstractReader<Object> {

    private final DataClassMap descriptor;

    public MapBindReader(String name, MapBody body, DataClassMap descriptor, TsonValueReaderResolver resolver,
                         Optional<SourcePosition> schemaPosition) {
        super(name, body, resolver, schemaPosition);
        this.descriptor = descriptor;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        ctx = ctx.withSchemaPosition(schemaPosition);
        Shape shape = expectMapShape(ctx);
        if (shape == Shape.MISMATCH) {
            return null;
        }
        try {
            Object mapData = descriptor.constructor().invoke(0);
            if (shape == Shape.ENTRIES) {
                readInto(ctx, (key, decodedValue) -> put(mapData, key, decodedValue));
            }
            return mapData;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("failed to construct " + descriptor.typeClass() + " from '" + name
                    + "'s own decoded entries", t);
        }
    }

    /** {@code descriptor.put()} is a {@link java.lang.invoke.MethodHandle}, declared to throw {@code Throwable} -- caught and rewrapped here since this is called from within a {@code BiConsumer}, which can't declare it. */
    private void put(Object mapData, Object key, Object decodedValue) {
        try {
            descriptor.put().invoke(mapData, key, decodedValue);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("failed to add an entry to " + descriptor.typeClass(), t);
        }
    }

    /**
     * Validates {@code typeDefinition} is map-shaped before ever constructing one, and resolves a
     * {@code descriptor} to build it with, always targeting {@code Map} but making a real effort to
     * get the key/value types right: the schema's own {@code key_type}/{@code value_type} names are
     * each resolved to a real bound Java class the same way any other schema type name is (falling
     * back to {@link String} only when a name has no real bound class at all, e.g. a synthesized,
     * materialized entry) -- see {@link ArrayBindReader.Factory}'s own Javadoc for the identical
     * reasoning, including why getting this right here doesn't actually change what a real consuming
     * field ends up bound to ({@link RecordBindReader}'s own rebind step always takes over there) or
     * what a direct read decodes (key/value narrowing never consults {@code descriptor} at all,
     * Java's own generics being erased at runtime regardless).
     */
    public static final class Factory implements ValueReaderFactory {

        private final DataBindContext context;

        public Factory(DataBindContext context) {
            this.context = context;
        }

        @Override
        public TsonValueReader<?> create(String name, TypeDefinition typeDefinition, ValueReaderContext context) {
            TsonValueReaderResolver resolver = context.readers();
            if (!(typeDefinition.body() instanceof MapBody body)) {
                throw new IllegalArgumentException("'" + name + "' is not map-shaped: " + typeDefinition.body());
            }
            Type keyType = resolveType(body.keyType().name());
            Type valueType = resolveType(body.valueType().name());
            DataClass dataClass = descriptorFor(new DataParameterizedType(Map.class, keyType, valueType));
            if (!(dataClass instanceof DataClassMap descriptor)) {
                throw new IllegalArgumentException("'" + name + "' resolves to " + dataClass.typeClass()
                        + ", which isn't map-shaped -- can't bind '" + name + "' as one");
            }
            return new MapBindReader(name, body, descriptor, resolver, typeDefinition.position());
        }

        /** {@code schemaTypeName} has no real bound Java class only for a synthesized, materialized type -- see this factory's own Javadoc. */
        private Class<?> resolveType(String schemaTypeName) {
            try {
                return context.getDescriptor(schemaTypeName).typeClass();
            } catch (DataBindException e) {
                return String.class;
            }
        }

        private DataClass descriptorFor(Type type) {
            try {
                return context.getDescriptor(Map.class, type);
            } catch (DataBindException e) {
                throw new IllegalStateException("no bound Java class for '" + Map.class.getName() + "'", e);
            }
        }
    }
}
