package io.ltr8.tson.json.reader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@code constructor name -> JsonValueReaderFactory} table. {@link #atoms()} is the one instance today.
 *
 * <p><b>There is no mode split yet, and that is a fact about atoms rather than a shortcut.</b> An atom reader
 * produces its family's natural host value whichever mode is compiling -- the same is true on the TSON side,
 * where tree mode merely wraps the leaf. The modes genuinely diverge at the containers, so the split arrives
 * with [TSON-JSON] §6 and not before.
 *
 * <p><b>An unregistered constructor is a gap, not a fault.</b> {@link #resolve} raises, {@code
 * JsonSchemaCompiler} catches, and the entry becomes a {@link JsonErrorReader} -- so a schema whose types this
 * encoding cannot yet read still compiles, and each unreadable value costs a verdict on itself alone. That is
 * how §6-§8's absence is currently spelled, and it is the same shape [TSON-SCHEMA] §2.2.2's extension point
 * will keep using afterwards.
 */
public final class JsonValueReaderFactoryRegistry implements JsonValueReaderFactoryResolver {

    /** The atom constructors meta-kernel.tn and meta.tn declare, in the order those documents declare them. */
    private static final List<String> ATOM_CONSTRUCTORS = List.of(
            // meta-kernel.tn
            "integer_type", "text_type", "uri_type", "regex_type",
            // meta.tn
            "bytes_type", "float_type", "decimal_type", "rational_type", "date_type", "time_type",
            "datetime_type", "duration_type", "period_type", "uuid_type", "complex_type", "mac_type",
            "email_type", "ipv4_type", "ipv6_type", "cidr4_type", "cidr6_type");

    private final Map<String, JsonValueReaderFactory> factories;

    private JsonValueReaderFactoryRegistry(Map<String, JsonValueReaderFactory> factories) {
        this.factories = factories;
    }

    /** [TSON-JSON] §5's whole vocabulary: every atom family, the enums, and the three {@code unit} instances. */
    public static JsonValueReaderFactoryRegistry atoms() {
        Map<String, JsonValueReaderFactory> factories = new LinkedHashMap<>();
        factories.put("unit", JsonAtomReader.UNIT);
        factories.put("enum", JsonAtomReader.ENUM);
        for (String constructor : ATOM_CONSTRUCTORS) {
            factories.put(constructor, JsonAtomReader.ATOM);
        }
        return new JsonValueReaderFactoryRegistry(Map.copyOf(factories));
    }

    @Override
    public JsonValueReaderFactory resolve(String name) {
        JsonValueReaderFactory factory = factories.get(name);
        if (factory == null) {
            throw new IllegalStateException("no JSON reader is registered for constructor '" + name
                    + "' -- [TSON-JSON] §6-§8 (containers, absence, discrimination) are not built yet");
        }
        return factory;
    }
}
