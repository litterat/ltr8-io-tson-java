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
 * <p><b>{@code namespace} registers to the {@code map} factory instance</b>: it composes {@code map} and adds no
 * field, so there is no separate shape to build a distinct factory for. This is a lookup-by-constructor-name
 * convenience only, as {@link RecordBindReader.Factory}'s own Javadoc states for every composite kind but
 * {@code record}.
 *
 * <p><b>{@code enum}</b> uses {@link AtomTypeReader#ATOM} in both {@link #tree} and {@link #bind}, so {@code boolean}
 * reads a genuine {@code Boolean}, not the text {@code "true"}/{@code "false"}. Tree mode additionally wraps every leaf
 * in a {@code TsonAtom}.
 *
 * <p><b>{@code choice} is shared between both modes</b>, registered once via {@link
 * ChoiceReader#FACTORY} -- see that class's own Javadoc for why it has no {@code
 * DataClassUnion}-bounded counterpart the way {@code record} does.
 *
 * <p><b>{@code declared_type} is shared between both modes</b>, registered once via {@link
 * DeclaredReader#FACTORY}: it selects the reader of the type the value names and builds nothing of its own.
 *
 * <p><b>Every {@code ~}-marked constructor the kernel and the three libraries declare has an entry</b> -- verified
 * against both files directly, not assumed -- and every one of them now builds a real reader.
 */
public final class ValueReaderFactoryRegistry implements ValueReaderFactoryResolver {

    private final Map<String, ValueReaderFactory> factories;
    private final DataBindContext binding;

    private ValueReaderFactoryRegistry(Map<String, ValueReaderFactory> factories, DataBindContext binding) {
        this.factories = factories;
        this.binding = binding;
    }

    /**
     * The context object-binding mode binds with, or {@code null} for tree mode: what a boundary hands the
     * annotations in front of a foreign value to the bound value with ({@link ExternReader}).
     */
    public DataBindContext binding() {
        return binding;
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
                AtomTypeReader.ATOM, AtomTypeReader.VOID, UnaryOperator.identity(),
                ChoiceReader.FACTORY), context);
    }

    /**
     * Tree mode: reads into an immutable {@link TsonValue}. The container factories
     * build node containers; every atom-family/enum factory is wrapped ({@link AtomTreeFactory}) so its leaf
     * yields a {@code TsonAtom} (or a {@code TsonVoid} where it produced no value), and {@code void_type}
     * yields a {@code TsonVoid} (see {@link #TREE_VOID}). Uses the object-binding enum factory so {@code
     * boolean} reads a real {@code Boolean} rather than the text {@code "true"}/{@code "false"}.
     */
    public static ValueReaderFactoryRegistry tree() {
        return new ValueReaderFactoryRegistry(baseFactories(
                new RecordTreeReader.Factory(), new ArrayTreeReader.Factory(), new MapTreeReader.Factory(),
                new TupleTreeReader.Factory(), AtomTypeReader.ATOM, TREE_VOID, AtomTreeFactory::new,
                ChoiceReader.FACTORY), null);
    }

    /** Tree mode's {@code void_type} factory: {@link VoidTreeReader} over {@link AtomTypeReader#VOID}'s reader. */
    private static final ValueReaderFactory TREE_VOID = (name, definition, context) ->
            new VoidTreeReader(AtomTypeReader.VOID.create(name, definition, context), AnnotationTypes.of(context));

    /** The atom constructors the kernel and the three libraries declare, by path. */
    private static final List<String> ATOM_CONSTRUCTORS = List.of(
            // meta.tn, under meta
            "numeric.integer_type", "unicode.text_type", "unicode.identifier_type", "unicode.regex_type", "net.iri_type",
            "meta.ref", "binary.bytes_type",
            // numeric.tn, under numeric
            "numeric.float_type", "numeric.decimal_type", "numeric.rational_type", "numeric.complex_type",
            // temporal.tn, under temporal
            "temporal.date_type", "temporal.time_type", "temporal.datetime_type", "temporal.duration_type",
            "temporal.period_type",
            // net.tn, under net
            "net.uri_type", "net.uuid_type", "net.email_type", "net.mac_type", "net.ipv4_type", "net.ipv6_type",
            "net.cidr4_type", "net.cidr6_type", "net.hostname_type", "net.host_type", "net.media_type_type");

    private static Map<String, ValueReaderFactory> baseFactories(ValueReaderFactory record, ValueReaderFactory array,
            ValueReaderFactory map, ValueReaderFactory tuple, ValueReaderFactory enumFactory,
            ValueReaderFactory voidFactory, UnaryOperator<ValueReaderFactory> leaf, ValueReaderFactory choice) {
        Map<String, ValueReaderFactory> factories = new LinkedHashMap<>();

        factories.put("meta.void_type", voidFactory);
        factories.put("meta.record", RecordDispatch.over(record));
        factories.put("meta.array", array);
        factories.put("meta.map", map);
        factories.put("meta.tuple", tuple);
        factories.put("meta.enum_type", leaf.apply(enumFactory));
        factories.put("meta.choice", choice);
        factories.put("meta.declared_type", DeclaredReader.FACTORY);

        // Every atom constructor routes to one factory, so what varies is the key and not the value:
        // AtomTypeReader.ATOM asks AtomParsers which parser the resolved body wants, and that mapping is
        // stated once, there. What the key set carries is the other fact -- *which constructors are atoms*
        // -- which nothing else states: BuiltinTypeVocabulary is keyed by type name (`int32`) rather than
        // constructor name (`integer_type`), and AtomParsers switches on the body's own class. Listing them
        // is also what makes a constructor this library has never seen, which any schema may declare, reach
        // NOT_IMPLEMENTED instead of being guessed at from whatever body it happened to resolve to.
        for (String atomConstructor : ATOM_CONSTRUCTORS) {
            factories.put(atomConstructor, leaf.apply(AtomTypeReader.ATOM));
        }

        // Collections.unmodifiableMap, not Map.copyOf -- preserves the LinkedHashMap's own insertion order
        // (Map.copyOf's own iteration order is unspecified), so the table reads at runtime as it does here.
        return Collections.unmodifiableMap(factories);
    }
}
