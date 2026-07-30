package io.ltr8.tson.compiler.reader;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataParameterizedType;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Object-binding mode's own {@code array} reader -- reads an array-shaped value into a real, bound
 * Java array/collection via {@code descriptor}, a {@code tson-bind} {@link DataClassArray} already
 * resolved for this array's own target Java type (resolving one is this class's caller's job, not
 * this class's -- same division of responsibility as {@link RecordBindReader}'s own {@code
 * DataClassRecord}).
 *
 * <p><b>Mirrors {@code TsonMapperReader.toArray} exactly, down to which {@link
 * java.lang.invoke.MethodHandle}s get called and in what order</b> -- {@code
 * descriptor.constructor().invoke(size)} to allocate the target with a known capacity, {@code
 * descriptor.iterator().invoke(arrayData)} once, then {@code descriptor.put().invoke(arrayData,
 * iterator, element)} per decoded element. {@link DataClassArray} already abstracts over a real Java
 * array vs. a {@code List}/{@code Set}/other collection via these same four handles, so this class
 * never has to guess or hand-pick a concrete collection type itself -- unlike {@link
 * RecordBindReader}, there's no per-element *narrowing* here either: each element's own binding
 * already happened recursively, inside whatever reader {@code resolver} produced for the element
 * type (this same class again if it's itself array-shaped, {@link RecordBindReader} if it's
 * record-shaped, and so on), so this class's own job is only routing each already-decoded element
 * into {@code descriptor}'s own assembly machinery.
 *
 * <p>Everything else -- resolving the element reader, confirming an array shape, size/uniqueness/
 * absent-element validation -- lives on {@link ArrayAbstractReader}; {@code unique_items} is still
 * enforced there regardless of what {@code descriptor}'s own backing collection would otherwise
 * silently tolerate.
 *
 * <p><b>A real Java array target needs its own exact final size known before construction, unlike a
 * growable {@code List}/{@code Set}</b> ({@code descriptor.constructor()} for an array type is
 * {@code MethodHandles.arrayConstructor}, genuinely fixed-size -- {@code put()}-ing past the
 * allocated length throws {@code ArrayIndexOutOfBoundsException}, not "grows"). A stream has no
 * up-front element count the way an already-built element list did, so {@link #read} branches on
 * {@code descriptor.typeClass().isArray()}: a real array decodes into a temporary buffer first, then
 * allocates and copies once the true count is known; every other (growable) target still constructs
 * empty and appends incrementally, genuinely one element at a time.
 */
final class ArrayBindReader extends ArrayAbstractReader<Object> {

    private final DataClassArray descriptor;

    public ArrayBindReader(String name, ArrayBody body, DataClassArray descriptor, TsonValueReaderResolver resolver,
                           Optional<SourcePosition> schemaPosition) {
        super(name, body, resolver, schemaPosition);
        this.descriptor = descriptor;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        ctx = ctx.withSchemaPosition(schemaPosition);
        if (!expectArrayStart(ctx)) {
            return null;
        }
        try {
            if (descriptor.typeClass().isArray()) {
                return readIntoFixedSizeArray(ctx);
            }
            Object arrayData = descriptor.constructor().invoke(0);
            Object iterator = descriptor.iterator().invoke(arrayData);
            readInto(ctx, decoded -> put(arrayData, iterator, decoded));
            return arrayData;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("failed to construct " + descriptor.typeClass() + " from '" + name
                    + "'s own decoded elements", t);
        }
    }

    private Object readIntoFixedSizeArray(TsonReadContext ctx) throws Throwable {
        List<Object> buffered = new ArrayList<>();
        readInto(ctx, buffered::add);
        Object arrayData = descriptor.constructor().invoke(buffered.size());
        Object iterator = descriptor.iterator().invoke(arrayData);
        for (Object decoded : buffered) {
            put(arrayData, iterator, decoded);
        }
        return arrayData;
    }

    /** {@code descriptor.put()} is a {@link java.lang.invoke.MethodHandle}, declared to throw {@code Throwable} -- caught and rewrapped here since this is called from within a {@code Consumer}, which can't declare it. */
    private void put(Object arrayData, Object iterator, Object decoded) {
        try {
            descriptor.put().invoke(arrayData, iterator, decoded);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("failed to add a decoded element to " + descriptor.typeClass(), t);
        }
    }

    /**
     * Validates {@code typeDefinition} is array-shaped before ever constructing one, and resolves a
     * {@code descriptor} to build it with, always targeting {@code List} (never a Java array or a
     * more specific collection -- there's no schema-level signal to prefer one over another) but
     * making a real effort to get the *element* type right: the schema's own {@code element_type}
     * name is resolved to a real bound Java class the same way any other schema type name is,
     * falling back to {@link String} only when that name has no real bound class at all -- {@code
     * ipv4_type}'s own {@code [value]}-sugared field is a real fixture example (materializes to a
     * synthesized array entry whose own {@code element_type} is {@code value}, and {@code
     * schema.meta} has no {@code Value} class for {@link io.ltr8.tson.compiler.config.SchemaMetaNameBinder}
     * to find by that name).
     * {@code token} specifically is a known, accepted imprecision the other direction: it resolves
     * *without* falling back, but to {@link io.ltr8.tson.schema.meta.Token} -- a real class, just the
     * wrong one (the raw literal wrapper §5.2/§5.10 field modifiers use, not the plain {@link String}
     * {@code token}'s own natural host type actually is) -- accepted rather than special-cased,
     * since (see below) the declared element type here never affects what a real read produces
     * either way.
     *
     * <p>This is still not guaranteed to be the target a real consuming field ultimately gets,
     * though. For a schema position reached through a record field, {@link RecordBindReader}'s own
     * rebind step (see its own Javadoc) discards this {@link ArrayBindReader} entirely and builds a
     * fresh one against that field's own real target type instead (e.g. {@code Set<String>}, a real
     * Java array, ...) -- {@link #read} never consults {@code descriptor}'s own element type at all
     * (only {@code constructor()}/{@code iterator()}/{@code put()}; the actual decoded elements come
     * from the schema-level element reader {@code resolver} produces, independent of anything built
     * here), so even where this factory's own best-effort element type is wrong, nothing downstream
     * reading through the *rebound* reader is affected. A caller reading directly through *this*
     * class's own build, bypassing the rebind step entirely (nothing does today), would still decode
     * every element correctly regardless -- {@code List.add(Object)} accepts any value regardless of
     * the list's own declared generic parameter, since Java generics are erased at runtime -- but
     * getting the declared element type right here is still worth doing, rather than leaning on that
     * erasure as a hidden safety net.
     */
    public static final class Factory implements ValueReaderFactory {

        private final DataBindContext context;

        public Factory(DataBindContext context) {
            this.context = context;
        }

        @Override
        public TsonValueReader<?> create(String name, TypeDefinition typeDefinition, TsonValueReaderResolver resolver) {
            if (!(typeDefinition.body() instanceof ArrayBody body)) {
                throw new IllegalArgumentException("'" + name + "' is not array-shaped: " + typeDefinition.body());
            }
            Type elementType = resolveElementType(body.elementType().name());
            DataClass dataClass = descriptorFor(new DataParameterizedType(List.class, elementType));
            if (!(dataClass instanceof DataClassArray descriptor)) {
                throw new IllegalArgumentException("'" + name + "' resolves to " + dataClass.typeClass()
                        + ", which isn't array-shaped -- can't bind '" + name + "' as one");
            }
            return new ArrayBindReader(name, body, descriptor, resolver, typeDefinition.position());
        }

        /** {@code schemaTypeName} has no real bound Java class only for a synthesized, materialized type -- see this factory's own Javadoc. */
        private Class<?> resolveElementType(String schemaTypeName) {
            try {
                return context.getDescriptor(schemaTypeName).typeClass();
            } catch (DataBindException e) {
                return String.class;
            }
        }

        private DataClass descriptorFor(Type type) {
            try {
                return context.getDescriptor(List.class, type);
            } catch (DataBindException e) {
                throw new IllegalStateException("no bound Java class for '" + List.class.getName() + "'", e);
            }
        }
    }
}
