package io.ltr8.tson.parser.bind;

import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.tson.parser.base.NumberNarrowing;
import io.ltr8.tson.parser.compiler.RecordParser.RecordBuilder;
import io.ltr8.tson.parser.compiler.RecordParser.RecordShape;
import io.ltr8.tson.parser.compiler.RecordParser.RecordShapeFactory;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Object-binding mode's own {@link RecordShapeFactory}: produces real, bound {@code schema.meta}
 * Java objects (e.g. a real {@link io.ltr8.tson.schema.meta.IntegerType}) instead of DOM mode's
 * plain {@code Map<String, Object>}. All construction is delegated to {@code tson-bind}'s own
 * {@link DataClassRecord} descriptor (constructor selection, {@code MethodHandle} binding, {@code
 * Optional}-wrapping) -- nothing here reimplements any of that; this class only narrows a
 * schema-produced value to a field's own declared width where they legitimately differ (see {@link
 * ObjectRecordBuilder#narrow}).
 *
 * <p>Holds nothing but {@link TsonObjectBinder#bind}'s own already-validated result -- a plain,
 * immutable {@code Map<String, DataClassRecord>}, one entry per {@code record}-shaped schema type
 * (see that class's own Javadoc for how it's built, including why an entry resolving to a
 * non-record Java class is silently absent from the map rather than a binding failure). {@link
 * #shapeFor} only ever consults this map, never resolves a class itself, so an entry {@link
 * TsonObjectBinder#bind} didn't already validate can't silently slip through later.
 */
public final class ObjectRecordShapeFactory implements RecordShapeFactory<Object> {

    private final Map<String, DataClassRecord> bound;

    public ObjectRecordShapeFactory(Map<String, DataClassRecord> bound) {
        this.bound = bound;
    }

    @Override
    public RecordShape<Object> shapeFor(String typeName, TypeDefinition definition, RecordBody body) {
        DataClassRecord descriptor = bound.get(typeName);
        if (descriptor == null) {
            throw new IllegalStateException("'" + typeName + "' was never bound -- call "
                    + "TsonObjectBinder.bind(TsonSchema, DataBindContext, TsonTypeNameBinder) with the "
                    + "governing schema before compiling against it (see TsonObjectBinding#factoryRegistry)");
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
         * NumberNarrowing}, the same utility {@code atom}'s numeric family and {@code
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
