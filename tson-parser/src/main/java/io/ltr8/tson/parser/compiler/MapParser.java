package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.ast.AbsentValue;
import io.ltr8.tson.parser.ast.CoreValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.EmptyBrace;
import io.ltr8.tson.parser.ast.MapValue;
import io.ltr8.tson.schema.meta.MapBody;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link TsonParserFactory} for meta-kernel's {@code map} constructor (§4.2, backing e.g. the
 * kernel's own {@code schema => map<type_name, type_definition>}), and the {@link TsonSchemaTypeParser}
 * it builds -- same shape as {@link RecordParser}/{@link ArrayParser} (DOM-mode, {@code Map<Object,
 * Object>}). Both {@code key_type} and {@code value_type} resolve eagerly at compile time, the same
 * reasoning as {@link ArrayParser}'s own element type: unconditionally needed for every entry.
 *
 * <p>{@code {}} reads as zero entries, matching {@code TsonMapperReader.toMap}'s own {@link
 * EmptyBrace} treatment (§2.8's own ambiguity resolved by the schema position, same as every other
 * composite here). A map key must not be the absent sentinel {@code _} (§2.9) -- checked
 * explicitly, the same rule {@code TsonMapperReader.toMap} enforces, since nothing between the
 * parser and here is positioned to. {@code min_items}/{@code max_items} validate entry count,
 * matching {@link ArrayParser}'s own treatment of the identical field pair.
 */
final class MapParser implements TsonSchemaTypeParser<Map<Object, Object>> {

    static final TsonParserFactory FACTORY = (_, name, definition, ctx) -> {
        MapBody body = (MapBody) definition.body();
        TsonSchemaTypeParser<?> keyParser = ctx.resolve(body.keyType().name());
        TsonSchemaTypeParser<?> valueParser = ctx.resolve(body.valueType().name());
        return new MapParser(name, body, keyParser, valueParser);
    };

    private final String name;
    private final MapBody body;
    private final TsonSchemaTypeParser<?> keyParser;
    private final TsonSchemaTypeParser<?> valueParser;

    private MapParser(String name, MapBody body, TsonSchemaTypeParser<?> keyParser, TsonSchemaTypeParser<?> valueParser) {
        this.name = name;
        this.body = body;
        this.keyParser = keyParser;
        this.valueParser = valueParser;
    }

    @Override
    public Map<Object, Object> read(DataValue value) {
        if (value == null) {
            throw new IllegalArgumentException("expected a map for '" + name + "', found no value");
        }
        CoreValue core = value.coreValue();
        List<MapValue.MapEntry> entries;
        if (core instanceof MapValue mv) {
            entries = mv.entries();
        } else if (core instanceof EmptyBrace) {
            entries = List.of();
        } else {
            throw new IllegalArgumentException("expected a map for '" + name + "', found " + core);
        }
        validateSize(entries.size());

        Map<Object, Object> result = new LinkedHashMap<>();
        for (MapValue.MapEntry entry : entries) {
            if (entry.key().coreValue() instanceof AbsentValue) {
                throw new IllegalArgumentException(
                        "'" + name + "': the absent sentinel '_' must not appear as a map key (§2.9)");
            }
            Object key = keyParser.read(entry.key());
            Object decodedValue = valueParser.read(entry.value().value());
            result.put(key, decodedValue);
        }
        return result;
    }

    private void validateSize(int size) {
        body.minItems().ifPresent(min -> {
            if (BigInteger.valueOf(size).compareTo(min) < 0) {
                throw new IllegalArgumentException(
                        "'" + name + "' has " + size + " entries, fewer than the minimum " + min);
            }
        });
        body.maxItems().ifPresent(max -> {
            if (BigInteger.valueOf(size).compareTo(max) > 0) {
                throw new IllegalArgumentException(
                        "'" + name + "' has " + size + " entries, more than the maximum " + max);
            }
        });
    }
}
