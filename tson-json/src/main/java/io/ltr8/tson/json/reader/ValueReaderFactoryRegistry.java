package io.ltr8.tson.json.reader;

import io.ltr8.bind.DataBindContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * A {@code constructor name -> ValueReaderFactory} table, one per read mode: {@link #tree()} and
 * {@link #bind(DataBindContext)}.
 *
 * <p><b>The atom factories are shared and only the wrapper differs.</b> An atom reader produces its family's
 * natural host value whichever mode is compiling, so tree mode wraps each leaf to yield the node the document
 * carried instead ({@link TreeAtomReader}) and changes nothing about what was parsed or refused, and bind mode
 * leaves it bare for a record to bind to a component.
 *
 * <p><b>What places a value is shared too.</b> A record family's dispatchers ({@link DispatchFactories}), a
 * family-base template's, the choice's ({@link DispatchChoiceReader}) and a declared position's ({@link
 * DispatchDeclaredReader}) select a reader and build nothing,
 * so every mode registers the same ones. So does a concrete record's loop ({@link RecordReader}); what is the
 * mode's own is the factory that builds it and the {@link RecordBuilder} it hands its slots to.
 *
 * <p><b>An unregistered constructor is a gap, not a fault.</b> {@link #resolve} raises, {@code
 * JsonSchemaCompiler} catches, and the entry becomes a {@link ErrorReader} -- so a schema whose types this
 * encoding cannot read still compiles, and each unreadable value costs a verdict on itself alone. Every
 * constructor the bundled schemas declare is registered, so what reaches this is a constructor another schema
 * declares.
 */
public final class ValueReaderFactoryRegistry implements ValueReaderFactoryResolver {

    /** The atom constructors the kernel and the three libraries declare, by path. */
    private static final List<String> ATOM_CONSTRUCTORS = List.of(
            // meta.tn, under meta
            "numeric.integer_type", "unicode.text_type", "unicode.identifier_type", "unicode.regex_type", "net.iri_type",
            "meta.reference", "binary.bytes_type",
            // numeric.tn, under numeric
            "numeric.float_type", "numeric.decimal_type", "numeric.rational_type", "numeric.complex_type",
            // temporal.tn, under temporal
            "temporal.date_type", "temporal.time_type", "temporal.datetime_type", "temporal.duration_type",
            "temporal.period_type",
            // net.tn, under net
            "net.uri_type", "net.uuid_type", "net.email_type", "net.mac_type", "net.ipv4_type", "net.ipv6_type",
            "net.cidr4_type", "net.cidr6_type", "net.hostname_type", "net.host_type", "net.media_type_type");

    private final Map<String, ValueReaderFactory> factories;

    private ValueReaderFactoryRegistry(Map<String, ValueReaderFactory> factories) {
        this.factories = factories;
    }

    /**
     * [TSON-JSON] §5's vocabulary alone, with no mode over it: every atom family, the enums, {@code value} and
     * {@code void}, each reading to its family's natural host value. No container constructor is
     * registered, so a schema using one compiles to a gap.
     *
     * <p>This is what a mode's registry is built over rather than a mode of its own -- {@link #tree} wraps
     * each leaf to yield the node the document carried instead. It is also the registry that answers the
     * question a mode hides: <em>what did the parser produce</em>, which tree mode discards by design.
     */
    public static ValueReaderFactoryRegistry atoms() {
        Map<String, ValueReaderFactory> factories = vocabulary(UnaryOperator.identity());
        factories.put("meta.template", DispatchFactories.TEMPLATE);
        return new ValueReaderFactoryRegistry(Map.copyOf(factories));
    }

    /**
     * Tree mode: the document comes back as a {@link io.ltr8.tson.json.tree.JsonValue}, validated.
     *
     * <p>A schema-directed tree read answers <em>does this document conform</em> -- the atom parsers run,
     * which is the validation, and their host values are discarded. It yields a JSON tree and never a TSON
     * one: converting an encoding is a different operation from reading one, and a caller who wants a typed
     * value reads in bind mode, where a class says what to build.
     */
    public static ValueReaderFactoryRegistry tree() {
        Map<String, ValueReaderFactory> factories = vocabulary(TreeAtomReader::over);
        factories.put("meta.record", DispatchFactories.over(TreeRecordBuilder.FACTORY));
        factories.put("meta.array", TreeArrayBuilder.FACTORY);
        // A `set` resolves to an ArrayBody like `array` itself -- refinement never adds or removes a field --
        // so the same factory serves it and the body's own `unique_items` is what separates them.
        factories.put("meta.set_type", TreeArrayBuilder.FACTORY);
        factories.put("meta.tuple", TreeTupleBuilder.FACTORY);
        factories.put("meta.map", TreeMapBuilder.FACTORY);
        factories.put("meta.choice", DispatchChoiceReader.FACTORY);
        factories.put("meta.declared_type", DispatchDeclaredReader.FACTORY);
        factories.put("meta.template", DispatchFactories.TEMPLATE);
        return new ValueReaderFactoryRegistry(Map.copyOf(factories));
    }

    /**
     * Bind mode over {@code binding}: a record reads into the class {@code binding} resolves for its schema type,
     * and an atom into the host value its component holds -- the atom factories unwrapped, each family reading
     * to its natural host value until a record binds it to a component. The dispatchers are the same as tree
     * mode's, placing a value and building nothing.
     *
     * <p>Every container binds: a record into the class bound to its schema type, and an array, set, tuple or map
     * into its natural {@code List} or {@code Map} -- or, at a record component, into the class the component
     * declares ({@link BindTargets}).
     */
    public static ValueReaderFactoryRegistry bind(DataBindContext binding) {
        Map<String, ValueReaderFactory> factories = vocabulary(UnaryOperator.identity());
        factories.put("meta.record", DispatchFactories.over(BindRecordBuilder.factory(binding)));
        factories.put("meta.array", BindArrayBuilder.FACTORY);
        factories.put("meta.set_type", BindArrayBuilder.FACTORY);
        factories.put("meta.tuple", BindTupleBuilder.FACTORY);
        factories.put("meta.map", BindMapBuilder.FACTORY);
        factories.put("meta.choice", DispatchChoiceReader.FACTORY);
        factories.put("meta.declared_type", DispatchDeclaredReader.FACTORY);
        factories.put("meta.template", DispatchFactories.TEMPLATE);
        return new ValueReaderFactoryRegistry(Map.copyOf(factories));
    }

    /** §5's atom constructors, each leaf passed through {@code leaf} so a mode can wrap what it produces. */
    private static Map<String, ValueReaderFactory> vocabulary(UnaryOperator<ValueReaderFactory> leaf) {
        Map<String, ValueReaderFactory> factories = new LinkedHashMap<>();
        factories.put("meta.value_type", leaf.apply(AtomReader.VALUE));
        factories.put("meta.void_type", leaf.apply(AtomReader.VOID));
        factories.put("enum", leaf.apply(AtomReader.ENUM));
        for (String constructor : ATOM_CONSTRUCTORS) {
            factories.put(constructor, leaf.apply(AtomReader.ATOM));
        }
        return factories;
    }

    @Override
    public ValueReaderFactory resolve(String name) {
        ValueReaderFactory factory = factories.get(name);
        if (factory == null) {
            throw new IllegalStateException("no JSON reader is registered for constructor '" + name
                    + "', which no bundled schema declares");
        }
        return factory;
    }
}
