package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.compiler.ast.AbsentValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.EmptyBrace;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.schema.meta.MapBody;

import java.math.BigInteger;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Everything {@link MapDomReader} and {@link MapBindReader} share verbatim: resolving the key/value
 * types' own readers once at construction, unwrapping a map-shaped {@link DataValue} into its entry
 * list ({@code {}} reads as zero entries, matching {@code TsonMapperReader.toMap}'s own {@link
 * EmptyBrace} treatment), and decoding those entries one at a time -- validating {@code min_items}/
 * {@code max_items}, rejecting the absent sentinel {@code _} in key position (§2.9) -- handing each
 * decoded key/value pair to a {@link BiConsumer} rather than assembling a result itself, the same
 * reasoning {@link ArrayAbstractReader#readInto} documents for arrays.
 *
 * <p>Unlike {@link ArrayAbstractReader}, there's no {@code unique_items}/{@code ElementState}
 * concept here at all -- {@link MapBody} carries neither: a map's own keys are inherently unique by
 * construction (a duplicate key is "last value wins" via an ordinary {@code put}, matching {@code
 * TsonMapperReader.toMap}'s own note, not a validation error), and there's no per-entry
 * required/optional state for a value the way an array element or tuple position has.
 */
abstract class MapAbstractReader<T> implements TsonValueReader<T> {

    final String name;
    final MapBody body;
    final TsonValueReader<?> keyParser;
    final TsonValueReader<?> valueParser;

    MapAbstractReader(String name, MapBody body, TsonValueReaderResolver resolver) {
        this.name = name;
        this.body = body;
        this.keyParser = resolver.resolve(body.keyType().name());
        this.valueParser = resolver.resolve(body.valueType().name());
    }

    final List<MapValue.MapEntry> entries(DataValue value) {
        if (value == null) {
            throw new IllegalArgumentException("expected a map for '" + name + "', found no value");
        }
        CoreValue core = value.coreValue();
        if (core instanceof MapValue mv) {
            return mv.entries();
        }
        if (core instanceof EmptyBrace) {
            return List.of();
        }
        throw new IllegalArgumentException("expected a map for '" + name + "', found " + core);
    }

    /** Validates size, then decodes every entry in order, handing each key/value pair to {@code sink}. */
    final void readInto(List<MapValue.MapEntry> entries, BiConsumer<Object, Object> sink) {
        validateSize(entries.size());
        for (MapValue.MapEntry entry : entries) {
            if (entry.key().coreValue() instanceof AbsentValue) {
                throw new IllegalArgumentException(
                        "'" + name + "': the absent sentinel '_' must not appear as a map key (§2.9)");
            }
            Object key = keyParser.read(entry.key());
            Object decodedValue = valueParser.read(entry.value().value());
            sink.accept(key, decodedValue);
        }
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
