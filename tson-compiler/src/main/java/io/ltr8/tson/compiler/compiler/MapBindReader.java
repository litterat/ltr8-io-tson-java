package io.ltr8.tson.compiler.compiler;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataParameterizedType;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * Object-binding mode's own {@code map} reader -- reads a map-shaped value into a real, bound Java
 * map via {@code descriptor}, a {@code tson-bind} {@link DataClassMap} already resolved for this
 * map's own target Java type (same division of responsibility as {@link RecordBindReader}'s {@code
 * DataClassRecord}/{@link ArrayBindReader}'s {@code DataClassArray}).
 *
 * <p>Mirrors {@code TsonMapperReader.toMap} exactly: {@code descriptor.constructor().invoke(size)}
 * to allocate the target with a known capacity, then {@code descriptor.put().invoke(mapData, key,
 * value)} per decoded entry -- no iterator needed, unlike {@link DataClassMap}'s own *reading* side,
 * since writing a map only ever needs {@code put}. As with {@link ArrayBindReader}, there's no
 * narrowing at this level -- each key and value's own binding already happened recursively, inside
 * whatever reader {@code resolver} produced for its type.
 *
 * <p>Everything else -- resolving the key/value readers, unwrapping the incoming {@link DataValue},
 * size validation, rejecting an absent key -- lives on {@link MapAbstractReader}.
 */
final class MapBindReader extends MapAbstractReader<Object> {

    private final DataClassMap descriptor;

    public MapBindReader(String name, MapBody body, DataClassMap descriptor, ValueReaderResolver resolver) {
        super(name, body, resolver);
        this.descriptor = descriptor;
    }

    @Override
    public Object read(DataValue value) {
        List<MapValue.MapEntry> entries = entries(value);
        try {
            Object mapData = descriptor.constructor().invoke(entries.size());
            readInto(entries, (key, decodedValue) -> put(mapData, key, decodedValue));
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
        public TsonValueReader<?> create(String name, TypeDefinition typeDefinition, ValueReaderResolver resolver) {
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
            return new MapBindReader(name, body, descriptor, resolver);
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
