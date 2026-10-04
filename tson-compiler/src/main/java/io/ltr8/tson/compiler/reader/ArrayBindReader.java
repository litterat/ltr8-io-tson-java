package io.ltr8.tson.compiler.reader;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataParameterizedType;
import io.ltr8.tson.base.diagnostics.BindingDiagnostics;
import io.ltr8.tson.base.diagnostics.BindingDiagnostics.Handed;
import io.ltr8.tson.base.diagnostics.Refusal;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Object-binding mode's own {@code array} reader -- reads an array-shaped value into a real, bound
 * Java array/collection via {@code descriptor}, a {@code tson-bind} {@link DataClassArray} already
 * resolved for this array's own target Java type (resolving one is this class's caller's job, not
 * this class's -- same division of responsibility as {@link RecordBindReader}'s own {@code
 * DataClassRecord}).
 *
 * <p><b>Mirrors {@code TsonObjectReader.toArray} exactly, down to which {@link
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
 * void-element validation -- lives on {@link ArrayAbstractReader}; {@code unique_items} is still
 * enforced there regardless of what {@code descriptor}'s own backing collection would otherwise
 * silently tolerate.
 *
 * <p><b>Nothing is handed to the collection once the value is lost</b> ({@link ConstructionGuard}): a refused
 * element travels as {@code null}, which a collection that refuses {@code null} would throw on, so each
 * {@code put} is skipped once anything has been reported since the mark. What the collection itself throws --
 * a void element meeting an {@code ArrayDeque}, say -- is reported, a
 * {@code BIND_MISMATCH}: the schema admitted what the bound class cannot hold.
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

    public ArrayBindReader(String name, String displayName, ArrayBody body, DataClassArray descriptor,
                           TsonTypeReaderResolver resolver, SchemaLocation schemaLocation,
                           AnnotationTypes annotationTypes, boolean elementsAreNames) {
        super(name, displayName, body,
                ElementBridging.wrap(
                        AnnotationBoxing.wrap(resolver.resolve(body.elementType().name()),
                                descriptor.arrayDataClass(), annotationTypes),
                        descriptor.arrayDataClass()),
                schemaLocation, elementsAreNames);
        this.descriptor = descriptor;
    }

    /** Over an element reader already bound to {@code descriptor}'s element -- {@link BindTargets}' rebuild. */
    ArrayBindReader(String name, String displayName, ArrayBody body, DataClassArray descriptor,
                    TsonTypeReader<?> element, SchemaLocation schemaLocation, boolean elementsAreNames) {
        super(name, displayName, body, element, schemaLocation, elementsAreNames);
        this.descriptor = descriptor;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        TsonReadContext at = ctx.underDeclaration(schemaLocation);
        if (!expectArrayStart(at)) {
            return null;
        }
        int mark = ConstructionGuard.mark(at);
        if (descriptor.typeClass().isArray()) {
            return readIntoFixedSizeArray(at, mark);
        }
        Object arrayData;
        Object iterator;
        try {
            arrayData = descriptor.constructor().invoke(0);
            iterator = descriptor.iterator().invoke(arrayData);
        } catch (Throwable t) {
            at.report(rejected(t));
            arrayData = null;
            iterator = null;
        }
        Object collection = arrayData;
        Object cursor = iterator;
        readInto(at, decoded -> {
            if (!ConstructionGuard.abandoned(at, mark)) {
                try {
                    descriptor.put().invoke(collection, cursor, decoded);
                } catch (Throwable t) {
                    at.report(rejected(t));
                }
            }
        });
        return ConstructionGuard.abandoned(at, mark) ? null : collection;
    }

    private Object readIntoFixedSizeArray(TsonReadContext ctx, int mark) {
        List<Object> buffered = new ArrayList<>();
        readInto(ctx, buffered::add);
        if (ConstructionGuard.abandoned(ctx, mark)) {
            return null;
        }
        try {
            Object arrayData = descriptor.constructor().invoke(buffered.size());
            Object iterator = descriptor.iterator().invoke(arrayData);
            for (Object decoded : buffered) {
                descriptor.put().invoke(arrayData, iterator, decoded);
            }
            return arrayData;
        } catch (Throwable t) {
            ctx.report(rejected(t));
            return null;
        }
    }

    /** The bound collection refusing what this read handed it, which the schema admitted. */
    private Refusal rejected(Throwable cause) {
        return BindingDiagnostics.rejectedUnderSchema(descriptor.typeClass(), Handed.ELEMENTS, cause);
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
        public TsonTypeReader<?> create(String name, TypeDefinition typeDefinition, ValueReaderContext context) {
            TsonTypeReaderResolver resolver = context.readers();
            if (!(typeDefinition.body() instanceof ArrayBody body)) {
                throw new IllegalArgumentException("'" + name + "' is not array-shaped: " + typeDefinition.body());
            }
            Type elementType = resolveElementType(body.elementType().name());
            DataClass dataClass = descriptorFor(new DataParameterizedType(List.class, elementType));
            if (!(dataClass instanceof DataClassArray descriptor)) {
                throw new IllegalArgumentException("'" + name + "' resolves to " + dataClass.typeClass()
                        + ", which isn't array-shaped -- can't bind '" + name + "' as one");
            }
            return new ArrayBindReader(name, EntryDisplayName.of(name, typeDefinition), body, descriptor, resolver,
                    context.locationOf(name, typeDefinition),
                    AnnotationTypes.of(context), elementsAreNames(body, context));
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
