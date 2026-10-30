package io.ltr8.tson.compiler.reader;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataParameterizedType;
import io.ltr8.tson.base.diagnostics.BindingDiagnostics;
import io.ltr8.tson.base.diagnostics.BindingDiagnostics.Handed;
import io.ltr8.tson.base.diagnostics.Refusal;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.compiler.resolver.AliasChain;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.Top;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Object-binding mode's own {@code map} reader -- reads a map-shaped value into a real, bound Java
 * map via {@code descriptor}, a {@code ltr8-bind} {@link DataClassMap} already resolved for this
 * map's own target Java type (same division of responsibility as {@link RecordBindReader}'s {@code
 * DataClassRecord}/{@link ArrayBindReader}'s {@code DataClassArray}).
 *
 * <p>Mirrors {@code TsonObjectReader.toMap} exactly: {@code descriptor.constructor().invoke(size)}
 * to allocate the target with a known capacity, then {@code descriptor.put().invoke(mapData, key,
 * value)} per decoded entry -- no iterator needed, unlike {@link DataClassMap}'s own *reading* side,
 * since writing a map only ever needs {@code put}. Unlike {@link ArrayBindReader}, there's no fixed-
 * size-target concern here at all -- every map {@code ltr8-bind} constructs is growable, so this always
 * constructs empty ({@code invoke(0)}) and appends incrementally, one entry at a time, with no
 * buffer-then-allocate step. As with {@link
 * ArrayBindReader}, there's no narrowing at this level either -- each key and value's own binding
 * already happened recursively, inside whatever reader {@code resolver} produced for its type.
 *
 * <p>An entry whose value the document wrote as {@code _} arrives here as a {@code null} and is
 * {@code put} like any other, so the key is in the bound map and maps to nothing -- the closest a Java
 * {@code Map} comes to §2.9's "present with a void value", and distinguishable from a key never stated.
 * A map that refuses {@code null} -- a {@code ConcurrentHashMap} -- throws on it instead, which is reported
 * as a {@code BIND_MISMATCH}: the schema admitted what the bound class cannot hold.
 *
 * <p><b>Nothing is handed to the map once the value is lost</b> ({@link ConstructionGuard}): a refused key or
 * value travels as {@code null}, so each {@code put} is skipped once anything has been reported since the mark.
 *
 * <p>Everything else -- resolving the key/value readers, confirming a map shape, size validation, rejecting
 * an absent key and admitting a void value -- lives on {@link MapAbstractReader}.
 */
final class MapBindReader extends MapAbstractReader<Object> {

    private final DataClassMap descriptor;

    /**
     * The namespace type a value read here is a value of, where the map is a namespace type's: what a
     * position whose host holds a {@link Namespace} wraps the map in ({@link BindTargets}). {@code null} for a
     * map that is no namespace.
     */
    final String namespace;

    public MapBindReader(String name, String displayName, MapBody body, DataClassMap descriptor,
                         TsonTypeReaderResolver resolver, SchemaLocation schemaLocation,
                         AnnotationTypes annotationTypes, boolean keysAreNames, String namespace) {
        super(name, displayName, body,
                ElementBridging.wrap(AnnotationBoxing.wrap(resolver.resolve(body.keyType().name()),
                        descriptor.keyDataClass(), annotationTypes), descriptor.keyDataClass()),
                ElementBridging.wrap(AnnotationBoxing.wrap(resolver.resolve(body.valueType().name()),
                        descriptor.valueDataClass(), annotationTypes), descriptor.valueDataClass()),
                schemaLocation, keysAreNames);
        this.descriptor = descriptor;
        this.namespace = namespace;
    }

    /** Over key and value readers already bound to {@code descriptor}'s own -- {@link BindTargets}' rebuild. */
    MapBindReader(String name, String displayName, MapBody body, DataClassMap descriptor, TsonTypeReader<?> key,
                  TsonTypeReader<?> value, SchemaLocation schemaLocation, boolean keysAreNames, String namespace) {
        super(name, displayName, body, key, value, schemaLocation, keysAreNames);
        this.descriptor = descriptor;
        this.namespace = namespace;
    }


    @Override
    public Object read(TsonReadContext ctx) {
        TsonReadContext at = ctx.underDeclaration(schemaLocation);
        Shape shape = expectMapShape(at);
        if (shape == Shape.MISMATCH) {
            return null;
        }
        int mark = ConstructionGuard.mark(at);
        Object mapData;
        try {
            mapData = descriptor.constructor().invoke(0);
        } catch (Throwable t) {
            at.report(rejected(t));
            mapData = null;
        }
        Object map = mapData;
        if (shape == Shape.ENTRIES) {
            readInto(at, (key, decodedValue) -> {
                if (!ConstructionGuard.abandoned(at, mark)) {
                    try {
                        descriptor.put().invoke(map, key, decodedValue);
                    } catch (Throwable t) {
                        at.report(rejected(t));
                    }
                }
            });
        }
        return ConstructionGuard.abandoned(at, mark) ? null : map;
    }

    /** The bound map refusing what this read handed it, which the schema admitted. */
    private Refusal rejected(Throwable cause) {
        return BindingDiagnostics.rejectedUnderSchema(descriptor.typeClass(), Handed.ENTRIES, cause);
    }

    /**
     * Validates {@code typeDefinition} is map-shaped before ever constructing one, and resolves a
     * {@code descriptor} to build it with -- an {@link AnnotatedMap} for a namespace type's value, keeping its
     * keys' annotations and order, a {@code LinkedHashMap} for an ordered map, so the order the
     * document wrote is kept, and a {@code Map} otherwise -- making a real effort to
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
        public TsonTypeReader<?> create(String name, Top typeDefinition, ValueReaderContext context) {
            TsonTypeReaderResolver resolver = context.readers();
            if (!(typeDefinition instanceof MapBody body)) {
                throw new IllegalArgumentException("'" + name + "' is not map-shaped: " + typeDefinition);
            }
            Type keyType = resolveType(body.keyType().name());
            Type valueType = resolveType(body.valueType().name());
            // A value of a namespace type is a map of names, whose keys' annotations and order are part of what
            // an instance of the constructor holds (NamespaceInstanceReader).
            String terminal = AliasChain.terminal(name, context.schema().entries()::get);
            String namespace = context.linked().namespace(terminal).isPresent() ? terminal : null;
            Class<?> mapClass = namespace != null ? AnnotatedMap.class
                    : body.ordered() ? LinkedHashMap.class : Map.class;
            DataClass dataClass = descriptorFor(mapClass, new DataParameterizedType(mapClass, keyType, valueType));
            if (!(dataClass instanceof DataClassMap descriptor)) {
                throw new IllegalArgumentException("'" + name + "' resolves to " + dataClass.typeClass()
                        + ", which isn't map-shaped -- can't bind '" + name + "' as one");
            }
            return new MapBindReader(name, context.displayName(name, typeDefinition), body, descriptor, resolver,
                    context.locationOf(name), AnnotationTypes.of(context),
                    keysAreNames(body, context), namespace);
        }

        /** {@code schemaTypeName} has no real bound Java class only for a synthesized, materialized type -- see this factory's own Javadoc. */
        private Class<?> resolveType(String schemaTypeName) {
            try {
                return context.getDescriptor(schemaTypeName).typeClass();
            } catch (DataBindException e) {
                return String.class;
            }
        }

        private DataClass descriptorFor(Class<?> mapClass, Type type) {
            try {
                return context.getDescriptor(mapClass, type);
            } catch (DataBindException e) {
                throw new IllegalStateException("no bound Java class for '" + mapClass.getName() + "'", e);
            }
        }
    }
}
