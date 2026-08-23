package io.ltr8.tson.compiler.reader;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.tree.TsonValue;

import java.util.Collections;
import java.util.LinkedHashMap;
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
 * #bind} (dispatching {@code boolean} to a real {@code Boolean} via {@link BooleanReader}, every other
 * member name through the ordinary path) -- so {@code boolean} reads a genuine {@code Boolean}, not the
 * text {@code "true"}/{@code "false"}. Tree mode additionally wraps every leaf in a {@code TsonAtom}.
 *
 * <p><b>{@code choice} is shared between both modes</b>, registered once via {@link
 * ChoiceReader#FACTORY} -- see that class's own Javadoc for why it has no {@code
 * DataClassUnion}-bounded counterpart the way {@code record} does.
 *
 * <p><b>Every {@code ~}-marked constructor meta-kernel.tn1/meta.tn1 declare has an entry</b> --
 * verified against both files directly, not assumed. Two of them (see {@link #notImplemented}'s own
 * call sites, deliberately grouped at the bottom of {@link #baseFactories} rather than interleaved
 * with the working entries above) still have no compiled reader at all -- {@code extern} and {@code
 * unknown_type} -- registered anyway, to an {@link ErrorReader}, so a schema merely *declaring* one of
 * them still compiles; only reading a value against one actually fails. Neither is an ordinary missing
 * parser: {@code extern} is a whole absent mechanism (§7.8's mid-document schema scope switch) and
 * {@code unknown_type} is the universe of types, not a token shape.
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

    public static ValueReaderFactoryRegistry bind(DataBindContext context) {
        return bind(context, true);
    }

    /**
     * {@link #bind(DataBindContext)} with the schema-to-class agreement check made optional. Strict is the
     * default and lenient is the opt-out, because the two failures are not symmetric: a strict reader that is
     * wrong says so at startup, in one message, next to both names; a lenient one that is wrong drops a value
     * on every document and shows up much later as a field holding its default. Leniency is a real position
     * -- versioned evolution, where a v1 consumer deliberately reads a v2 document -- but it is a decision
     * worth writing down at the place that makes it.
     */
    public static ValueReaderFactoryRegistry bind(DataBindContext context, boolean strict) {
        return new ValueReaderFactoryRegistry(baseFactories(
                new RecordBindReader.Factory(context, strict), new ArrayBindReader.Factory(context),
                new MapBindReader.Factory(context), new TupleBindReader.Factory(context),
                AtomTypeReader.ENUM_OBJECT_MODE, AtomTypeReader.UNIT, UnaryOperator.identity(),
                ChoiceReader.FACTORY));
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
                ChoiceReader.FACTORY));
    }

    /** Tree mode's {@code unit} factory: {@code void} → {@link AbsentTreeReader}, {@code value}/{@code token} → {@link AtomTreeReader} over {@link AtomTypeReader#UNIT}'s own reader. */
    private static final ValueReaderFactory TREE_UNIT = (name, definition, context) ->
            "void".equals(name)
                    ? new AbsentTreeReader(AtomTypeReader.UNIT.create(name, definition, context),
                            AnnotationTypes.of(context))
                    : new AtomTreeReader(AtomTypeReader.UNIT.create(name, definition, context), name,
                            AnnotationTypes.of(context));

    private static Map<String, ValueReaderFactory> baseFactories(ValueReaderFactory record, ValueReaderFactory array,
            ValueReaderFactory map, ValueReaderFactory tuple, ValueReaderFactory enumFactory,
            ValueReaderFactory unitFactory, UnaryOperator<ValueReaderFactory> leaf,
            ValueReaderFactory choice) {
        Map<String, ValueReaderFactory> factories = new LinkedHashMap<>();

        // meta-kernel.tn1
        factories.put("unit", unitFactory);
        factories.put("integer_type", leaf.apply(AtomTypeReader.INTEGER_TYPE));
        factories.put("text_type", leaf.apply(AtomTypeReader.TEXT_TYPE));
        factories.put("uri_type", leaf.apply(AtomTypeReader.URI_TYPE));
        factories.put("regex_type", leaf.apply(AtomTypeReader.REGEX_TYPE));
        factories.put("record", record);
        factories.put("array", array);
        factories.put("set", array);
        factories.put("map", map);
        factories.put("tuple", tuple);
        factories.put("enum", leaf.apply(enumFactory));
        factories.put("choice", choice);

        // meta.tn1
        factories.put("binary", leaf.apply(AtomTypeReader.BINARY));
        factories.put("float_type", leaf.apply(AtomTypeReader.FLOAT_TYPE));
        factories.put("decimal_type", leaf.apply(AtomTypeReader.DECIMAL_TYPE));
        factories.put("rational_type", leaf.apply(AtomTypeReader.RATIONAL_TYPE));
        factories.put("date_type", leaf.apply(AtomTypeReader.DATE_TYPE));
        factories.put("time_type", leaf.apply(AtomTypeReader.TIME_TYPE));
        factories.put("datetime_type", leaf.apply(AtomTypeReader.DATETIME_TYPE));
        factories.put("duration_type", leaf.apply(AtomTypeReader.DURATION_TYPE));
        factories.put("uuid_type", leaf.apply(AtomTypeReader.UUID_TYPE));
        factories.put("complex_type", leaf.apply(AtomTypeReader.COMPLEX_TYPE));
        factories.put("mac_type", leaf.apply(AtomTypeReader.MAC_TYPE));
        factories.put("email_type", leaf.apply(AtomTypeReader.EMAIL_TYPE));
        factories.put("ipv4_type", leaf.apply(AtomTypeReader.IPV4_TYPE));
        factories.put("ipv6_type", leaf.apply(AtomTypeReader.IPV6_TYPE));
        factories.put("cidr4_type", leaf.apply(AtomTypeReader.CIDR4_TYPE));
        factories.put("cidr6_type", leaf.apply(AtomTypeReader.CIDR6_TYPE));

        // Sugar/alias names -- not their own `~`-marked constructors, kept for lookup convenience only.

        // ---- Not implemented yet -- every entry below is a real `~`-marked constructor from
        // ---- meta-kernel.tn1/meta.tn1 with no compiled reader at all. Registered to ErrorReader so a
        // ---- schema declaring one still compiles; only reading a value against one actually fails.
        factories.put("extern", notImplemented("extern"));
        factories.put("unknown_type", notImplemented("unknown_type"));

        // Collections.unmodifiableMap, not Map.copyOf -- preserves the LinkedHashMap's own insertion
        // order (Map.copyOf's own iteration order is unspecified), so the "not implemented" block
        // stays visibly last at runtime too, not just in source.
        return Collections.unmodifiableMap(factories);
    }

    private static ValueReaderFactory notImplemented(String constructorName) {
        return (name, typeDefinition, context) -> new ErrorReader(name, new UnsupportedOperationException(
                "'" + name + "' uses the '" + constructorName + "' constructor, which has no compiled reader "
                        + "implemented yet"));
    }
}
