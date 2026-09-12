package io.ltr8.tson.compiler.reader;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.tree.TsonValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * A {@code constructor name -> ValueReaderFactory} table, one per mode -- {@link #tree}/{@link
 * #bind(DataBindContext)} are the two instances a caller actually wants.
 *
 * <p><b>Fully self-contained within this package</b> -- every entry is either this package's own
 * composite factory ({@code record}/{@code array}/{@code map}/{@code tuple}) or one of {@link
 * AtomTypeReader}'s own constants, this package's own copy of the atom-family adapters. No
 * dependency on {@code reader.TsonParserFactoryRegistry} (which is going away) or anything else in
 * {@code reader} -- deliberate, while this package's own shape is still settling.
 *
 * <p><b>{@code set} registers to the exact same {@code array} factory instance</b> -- it resolves to an
 * {@code ArrayBody} like {@code array} itself, refinement never adding or removing a field, so there is
 * no separate shape to build a distinct factory for. This is
 * a *lookup-by-constructor-name* convenience only -- it says nothing about whether an {@code
 * array}-typed position should ever dispatch to {@code !set [...]} at read time, which {@link
 * RecordBindReader.Factory}'s own Javadoc deliberately does not attempt for any composite kind but
 * {@code record}.
 *
 * <p><b>{@code enum}</b> uses {@link AtomTypeReader#ENUM_OBJECT_MODE} in both {@link #tree} and {@link
 * #bind} (dispatching {@code boolean} to a real {@code Boolean} via the vocabulary's own
 * {@code BooleanParser}, every other
 * member name through the ordinary path) -- so {@code boolean} reads a genuine {@code Boolean}, not the
 * text {@code "true"}/{@code "false"}. Tree mode additionally wraps every leaf in a {@code TsonAtom}.
 *
 * <p><b>{@code choice} is shared between both modes</b>, registered once via {@link
 * ChoiceReader#FACTORY} -- see that class's own Javadoc for why it has no {@code
 * DataClassUnion}-bounded counterpart the way {@code record} does.
 *
 * <p><b>{@code scoped} is the one constructor whose two modes differ in what they keep rather than in what
 * they build</b> ({@link ScopedReader#TREE}/{@link ScopedReader#BIND}): both read a foreign value through the
 * foreign schema's own compiled reader, and only tree mode has somewhere to record the scope the document
 * pushed. Every scoped instance -- core's {@code declared}, {@code extern} and {@code dynamic}, and every
 * narrowing {@code extern_of}/{@code extern_type} materialises -- shares the one reader, what separates them
 * being two constraint values rather than a shape.
 *
 * <p><b>Every {@code ~}-marked constructor meta-kernel.tn/meta.tn declare has an entry</b> -- verified
 * against both files directly, not assumed -- and every one of them now builds a real reader.
 */
public final class ValueReaderFactoryRegistry implements ValueReaderFactoryResolver {

    private final Map<String, ValueReaderFactory> factories;

    private ValueReaderFactoryRegistry(Map<String, ValueReaderFactory> factories) {
        this.factories = factories;
    }

    @Override
    public ValueReaderFactory resolve(String name) {
        ValueReaderFactory factory = factories.get(name);
        if (factory == null) {
            throw new IllegalStateException("no ValueReaderFactory registered for constructor '" + name + "'");
        }
        return factory;
    }

    /**
     * Object-binding mode. Every record reader checks its schema against the class bound to it as it is
     * built, so a disagreement is a {@code BindMismatchException} at compile rather than a value quietly
     * lost on every read.
     */
    public static ValueReaderFactoryRegistry bind(DataBindContext context) {
        return new ValueReaderFactoryRegistry(baseFactories(
                new RecordBindReader.Factory(context), new ArrayBindReader.Factory(context),
                new MapBindReader.Factory(context), new TupleBindReader.Factory(context),
                AtomTypeReader.ENUM_OBJECT_MODE, AtomTypeReader.UNIT, UnaryOperator.identity(),
                ChoiceReader.FACTORY, ScopedReader.BIND));
    }

    /**
     * Tree mode: reads into an immutable {@link TsonValue}. The container factories
     * build node containers; every atom-family/enum factory is wrapped ({@link AtomTreeFactory}) so its leaf
     * yields a {@code TsonAtom} (or a {@code TsonAbsent} where it produced no value), and {@code unit}'s
     * {@code void} yields a {@code TsonAbsent} (see {@link #TREE_UNIT}). Uses the object-binding enum factory so {@code boolean} reads a real {@code
     * Boolean} rather than the text {@code "true"}/{@code "false"}.
     */
    public static ValueReaderFactoryRegistry tree() {
        return new ValueReaderFactoryRegistry(baseFactories(
                new RecordTreeReader.Factory(), new ArrayTreeReader.Factory(), new MapTreeReader.Factory(),
                new TupleTreeReader.Factory(), AtomTypeReader.ENUM_OBJECT_MODE, TREE_UNIT, AtomTreeFactory::new,
                ChoiceReader.FACTORY, ScopedReader.TREE));
    }

    /** Tree mode's {@code unit} factory: {@code void} → {@link AbsentTreeReader}, {@code value}/{@code token} → {@link AtomTreeReader} over {@link AtomTypeReader#UNIT}'s own reader. */
    private static final ValueReaderFactory TREE_UNIT = (name, definition, context) ->
            "void".equals(name)
                    ? new AbsentTreeReader(AtomTypeReader.UNIT.create(name, definition, context),
                            AnnotationTypes.of(context))
                    : new AtomTreeReader(AtomTypeReader.UNIT.create(name, definition, context), name,
                            AnnotationTypes.of(context));

    /** The atom constructors meta-kernel.tn and meta.tn declare, in the order those documents declare them. */
    private static final List<String> ATOM_CONSTRUCTORS = List.of(
            // meta-kernel.tn
            "integer_type", "text_type", "uri_type", "regex_type",
            // meta.tn
            "bytes_type", "float_type", "decimal_type", "rational_type", "date_type", "time_type",
            "datetime_type", "duration_type", "period_type", "uuid_type", "complex_type", "mac_type",
            "email_type", "ipv4_type", "ipv6_type", "cidr4_type", "cidr6_type");

    private static Map<String, ValueReaderFactory> baseFactories(ValueReaderFactory record, ValueReaderFactory array,
            ValueReaderFactory map, ValueReaderFactory tuple, ValueReaderFactory enumFactory,
            ValueReaderFactory unitFactory, UnaryOperator<ValueReaderFactory> leaf,
            ValueReaderFactory choice, ValueReaderFactory scoped) {
        Map<String, ValueReaderFactory> factories = new LinkedHashMap<>();

        // meta-kernel.tn
        factories.put("unit", unitFactory);
        factories.put("record", RecordDispatch.over(record));
        factories.put("array", array);
        factories.put("set_type", array);
        factories.put("map", map);
        factories.put("tuple", tuple);
        factories.put("enum", leaf.apply(enumFactory));
        factories.put("choice", choice);

        // meta.tn
        factories.put("scoped", scoped);

        // Every atom constructor routes to one factory, so what varies is the key and not the value:
        // AtomTypeReader.ATOM asks AtomParsers which parser the resolved body wants, and that mapping is
        // stated once, there. What the key set carries is the other fact -- *which constructors are atoms*
        // -- which nothing else states: BuiltinTypeVocabulary is keyed by type name (`int32`) rather than
        // constructor name (`integer_type`), and AtomParsers switches on the body's own class. Listing them
        // is also what makes a constructor this library has never seen reach NOT_IMPLEMENTED (§2.2.2's
        // extension point) instead of being guessed at from whatever body it happened to resolve to.
        for (String atomConstructor : ATOM_CONSTRUCTORS) {
            factories.put(atomConstructor, leaf.apply(AtomTypeReader.ATOM));
        }

        // Collections.unmodifiableMap, not Map.copyOf -- preserves the LinkedHashMap's own insertion order
        // (Map.copyOf's own iteration order is unspecified), so the table reads at runtime as it does here.
        return Collections.unmodifiableMap(factories);
    }
}
