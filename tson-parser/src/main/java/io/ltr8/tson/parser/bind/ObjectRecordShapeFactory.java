package io.ltr8.tson.parser.bind;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.tson.parser.resolver.NumberNarrowing;
import io.ltr8.tson.parser.resolver.schema.compiled.RecordParser.RecordBuilder;
import io.ltr8.tson.parser.resolver.schema.compiled.RecordParser.RecordShape;
import io.ltr8.tson.parser.resolver.schema.compiled.RecordParser.RecordShapeFactory;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Object-binding mode's own {@link RecordShapeFactory}: produces real, bound {@code schema.meta}
 * Java objects (e.g. a real {@link io.ltr8.tson.schema.meta.IntegerType}) instead of DOM mode's
 * plain {@code Map<String, Object>}. All construction is delegated to {@code tson-bind}'s own
 * {@link DataClassRecord} descriptor (constructor selection, {@code MethodHandle} binding, {@code
 * Optional}-wrapping) -- nothing here reimplements any of that; this class only decides *which*
 * Java class a schema type name maps to (via {@link TsonTypeNameBinder}) and narrows a schema-produced
 * value to that class's own declared field width where they legitimately differ (see {@link
 * ObjectRecordBuilder#narrow}).
 *
 * <p><b>Binding happens eagerly, at {@link #validate}, not lazily per read.</b> {@link
 * TsonObjectBinding#factoryRegistry} calls it once, up front, walking every {@code record}-shaped entry
 * in the whole schema and resolving+validating a {@link DataClassRecord} descriptor for each --
 * both "does {@code binder} know a matching class" and "can {@code tson-bind} actually build a
 * descriptor for it" (e.g. the {@code @Record}-on-canonical-constructor gotcha documented elsewhere
 * in this codebase). A schema with a genuine, unresolvable entry still *registers* fine (schema
 * validation/materialization, in {@code tson-schema}, is unaffected) -- it just can't be *compiled*
 * for object-binding mode, and fails clearly, with every problem entry named at once, rather than
 * one at a time as unrelated reads happen to reach them.
 *
 * <p><b>An entry that resolves to a real, existing Java class which isn't a record is silently
 * skipped, not treated as a failure.</b> A handful of real meta-kernel entries (its own {@code
 * atom}/{@code product}/{@code sum}/{@code top} base-kind declarations, and {@code type_argument})
 * mangle to a genuine {@code schema.meta} class that's deliberately a sealed marker interface, not
 * a plain record (see {@link SchemaMetaTypeNameBinder}'s own Javadoc) -- these are meta-schema
 * machinery real application data is never actually read as an instance of, so this factory was
 * never going to be the right mechanism to construct them regardless; failing the whole schema's
 * compilation over them would be a false positive -- confirmed empirically, not assumed, by running
 * this validation against the real, fully registered meta-kernel.tn1 fixture (0 genuine problems,
 * 5 legitimately skipped, 23 bound).
 */
public final class ObjectRecordShapeFactory implements RecordShapeFactory<Object> {

    private final DataBindContext context;
    private final TsonTypeNameBinder binder;
    private final Map<String, DataClassRecord> validated = new LinkedHashMap<>();

    public ObjectRecordShapeFactory(DataBindContext context) {
        this(context, SchemaMetaTypeNameBinder.INSTANCE);
    }

    public ObjectRecordShapeFactory(DataBindContext context, TsonTypeNameBinder binder) {
        this.context = context;
        this.binder = binder;
    }

    /**
     * Walks every {@code record}-shaped entry in {@code schema} (i.e. every entry whose {@link
     * TypeDefinition#body()} is a {@link RecordBody} -- the ones that would actually reach {@link
     * #shapeFor} once compiled), resolving and caching a {@link DataClassRecord} for each. Must run
     * before this factory compiles anything (see {@link TsonObjectBinding#factoryRegistry}) -- {@link
     * #shapeFor} only ever consults this cache, never {@code binder} directly, so an entry this
     * method didn't already validate can't silently slip through later.
     *
     * @throws IllegalStateException naming every entry that failed to resolve, if any did
     */
    public void validate(TsonSchema schema) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, TypeDefinition> entry : schema.entries().entrySet()) {
            String name = entry.getKey();
            if (!(entry.getValue().body() instanceof RecordBody)) {
                continue;
            }
            Class<?> target;
            try {
                target = binder.resolve(name);
            } catch (ClassNotFoundException e) {
                problems.add("'" + name + "': " + e.getMessage());
                continue;
            }
            DataClass descriptor;
            try {
                descriptor = context.getDescriptor(target);
            } catch (DataBindException e) {
                problems.add("'" + name + "' resolved to " + target + ", but tson-bind could not build a "
                        + "descriptor for it: " + e.getMessage());
                continue;
            }
            if (!(descriptor instanceof DataClassRecord recordDescriptor)) {
                // Not a failure -- a real class that isn't a record (e.g. Top/Atom/Product/Sum,
                // deliberately sealed marker interfaces) is meta-schema machinery this factory was
                // never going to construct anyway; see this class's own Javadoc.
                continue;
            }
            validated.put(name, recordDescriptor);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("object-binding mode could not resolve a Java class for "
                    + problems.size() + (problems.size() == 1 ? " schema entry" : " schema entries") + ":\n  "
                    + String.join("\n  ", problems));
        }
    }

    @Override
    public RecordShape<Object> shapeFor(String typeName, TypeDefinition definition, RecordBody body) {
        DataClassRecord descriptor = validated.get(typeName);
        if (descriptor == null) {
            throw new IllegalStateException("'" + typeName + "' was never validated -- call "
                    + "ObjectRecordShapeFactory.validate(TsonSchema) with the governing schema before "
                    + "compiling against it (see TsonObjectBinding#factoryRegistry)");
        }
        return () -> new ObjectRecordBuilder(descriptor);
    }

    private static final class ObjectRecordBuilder implements RecordBuilder<Object> {
        private final DataClassRecord descriptor;
        private final Map<String, Object> values = new LinkedHashMap<>();

        private ObjectRecordBuilder(DataClassRecord descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public void field(String name, Object value) {
            values.put(name, value);
        }

        @Override
        public Object build() {
            DataClassField[] fields = descriptor.fields();
            Object[] construct = new Object[fields.length];
            for (DataClassField field : fields) {
                construct[field.index()] = narrow(values.get(field.name()), field.type());
            }
            try {
                return descriptor.constructor().invoke(construct);
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException("failed to construct " + descriptor.typeClass() + " from its "
                        + "compiled field values", t);
            }
        }

        /**
         * The schema-driven child-parser recursion has no knowledge of the target Java field's own
         * declared width -- e.g. {@code text_type}'s {@code min_length}/{@code max_length} are the
         * schema's own unconstrained {@code integer} atom, whose natural host type is {@link
         * BigInteger}, but {@code TextType.minLength} is {@code Optional<Integer>}. Reuses {@link
         * NumberNarrowing}, the same utility {@code resolver.vocab}'s numeric family and {@code
         * io.ltr8.tson.parser.mapper}'s untyped-number binding already share for exactly this
         * purpose, rather than a second copy of the same logic.
         *
         * <p>Two more narrowings, both found the same way (a real fixture field, not anticipated up
         * front): a schema {@code enum}-typed field (e.g. {@code complex_type}'s own {@code
         * component: complex_component}) reads as the enum's own raw member text ({@link String}),
         * narrowed here to the matching Java {@code enum} constant by exact name (e.g. {@link
         * io.ltr8.tson.schema.meta.ComplexType.Component}, {@link
         * io.ltr8.tson.schema.meta.FloatType.Format}, {@link
         * io.ltr8.tson.schema.meta.BinaryType.Encoding} -- every schema-side member name matches its
         * Java constant's name exactly, confirmed against the real fixture, not assumed) -- the same
         * schema-driven-vs-reflection-driven gap {@code BooleanParser}'s own Javadoc documents for
         * {@code boolean}, just resolved generically here instead of via a second name-keyed factory,
         * since {@code AtomTypeParser}'s own dispatch has no visibility into a record's *own* field
         * types the way this builder already does. A schema {@code uri}-typed field (e.g. {@code
         * atom_specification}'s own {@code spec: uri}, composed flat into {@code cidr4_type}/{@code
         * ipv4_type}/...) reads as a real {@link java.net.URI}, narrowed to {@link String} where the
         * target field deliberately keeps it flat (see {@code Cidr4Type}'s own Javadoc for why that
         * field is a bare {@code String}, not {@code URI}, in the first place).
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private static Object narrow(Object raw, Class<?> target) {
            if (raw instanceof BigInteger bi && target != BigInteger.class) {
                return NumberNarrowing.narrowIntegral(bi, target);
            }
            if (raw instanceof BigDecimal bd && target != BigDecimal.class) {
                return NumberNarrowing.narrowDecimal(bd, target);
            }
            if (raw instanceof String s && target.isEnum()) {
                return Enum.valueOf((Class<Enum>) target, s);
            }
            if (raw instanceof java.net.URI uri && target == String.class) {
                return uri.toString();
            }
            return raw;
        }
    }
}
