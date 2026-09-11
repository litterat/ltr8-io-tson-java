package io.ltr8.tson.json.reader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * A {@code constructor name -> ValueReaderFactory} table, one per read mode. {@link #tree()} is the one
 * instance today; bind mode joins it over the same containers.
 *
 * <p><b>The atom factories are shared and only the wrapper differs.</b> An atom reader produces its family's
 * natural host value whichever mode is compiling, so tree mode wraps each leaf to yield the node the document
 * carried instead ({@link TreeAtomReader}) and changes nothing about what was parsed or refused. The modes
 * genuinely diverge at the containers, which is why the split arrives with [TSON-JSON] §6.
 *
 * <p><b>An unregistered constructor is a gap, not a fault.</b> {@link #resolve} raises, {@code
 * JsonSchemaCompiler} catches, and the entry becomes a {@link ErrorReader} -- so a schema whose types this
 * encoding cannot yet read still compiles, and each unreadable value costs a verdict on itself alone. That is
 * how §6-§8's absence is currently spelled, and it is the same shape [TSON-SCHEMA] §2.2.2's extension point
 * will keep using afterwards.
 */
public final class ValueReaderFactoryRegistry implements ValueReaderFactoryResolver {

    /** The atom constructors meta-kernel.tn and meta.tn declare, in the order those documents declare them. */
    private static final List<String> ATOM_CONSTRUCTORS = List.of(
            // meta-kernel.tn
            "integer_type", "text_type", "uri_type", "regex_type",
            // meta.tn
            "bytes_type", "float_type", "decimal_type", "rational_type", "date_type", "time_type",
            "datetime_type", "duration_type", "period_type", "uuid_type", "complex_type", "mac_type",
            "email_type", "ipv4_type", "ipv6_type", "cidr4_type", "cidr6_type");

    private final Map<String, ValueReaderFactory> factories;

    private ValueReaderFactoryRegistry(Map<String, ValueReaderFactory> factories) {
        this.factories = factories;
    }

    /**
     * [TSON-JSON] §5's vocabulary alone, with no mode over it: every atom family, the enums, and the three
     * {@code unit} instances, each reading to its family's natural host value. No container constructor is
     * registered, so a schema using one compiles to a gap.
     *
     * <p>This is what a mode's registry is built over rather than a mode of its own -- {@link #tree} wraps
     * each leaf to yield the node the document carried instead. It is also the registry that answers the
     * question a mode hides: <em>what did the parser produce</em>, which tree mode discards by design.
     */
    public static ValueReaderFactoryRegistry atoms() {
        return new ValueReaderFactoryRegistry(Map.copyOf(vocabulary(UnaryOperator.identity())));
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
        factories.put("record", TreeRecordReader.FACTORY);
        factories.put("array", TreeArrayReader.FACTORY);
        // A `set` resolves to an ArrayBody like `array` itself -- refinement never adds or removes a field --
        // so the same factory serves it and the body's own `unique_items` is what separates them.
        factories.put("set_type", TreeArrayReader.FACTORY);
        factories.put("tuple", TreeTupleReader.FACTORY);
        factories.put("map", TreeMapReader.FACTORY);
        factories.put("choice", TreeChoiceReader.FACTORY);
        return new ValueReaderFactoryRegistry(Map.copyOf(factories));
    }

    /** §5's atom constructors, each leaf passed through {@code leaf} so a mode can wrap what it produces. */
    private static Map<String, ValueReaderFactory> vocabulary(UnaryOperator<ValueReaderFactory> leaf) {
        Map<String, ValueReaderFactory> factories = new LinkedHashMap<>();
        factories.put("unit", leaf.apply(AtomReader.UNIT));
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
                    + "' -- [TSON-JSON] §8.5 (scoped positions, the open sum) is not built yet");
        }
        return factory;
    }
}
