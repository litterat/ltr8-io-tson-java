package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.compiler.ast.AbsentValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.EmptyBrace;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
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
    final Optional<SourcePosition> schemaPosition;

    MapAbstractReader(String name, MapBody body, TsonValueReaderResolver resolver, Optional<SourcePosition> schemaPosition) {
        this.name = name;
        this.body = body;
        this.keyParser = resolver.resolve(body.keyType().name());
        this.valueParser = resolver.resolve(body.valueType().name());
        this.schemaPosition = schemaPosition;
    }

    /** Returns {@code null} (not a real entry list) on a shape mismatch -- see {@link RecordAbstractReader#dataFields} for the identical "caller must stop, not also report every entry missing" contract. */
    final List<MapValue.MapEntry> entries(DataValue value, TsonReadContext ctx) {
        if (value == null) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected a map for '" + name + "', found no value",
                    "a map", "no value");
            return null;
        }
        CoreValue core = value.coreValue();
        if (core instanceof MapValue mv) {
            return mv.entries();
        }
        if (core instanceof EmptyBrace) {
            return List.of();
        }
        ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected a map for '" + name + "', found " + core,
                "a map", String.valueOf(core));
        return null;
    }

    /**
     * Validates size, then decodes every entry in order, handing each key/value pair to {@code sink}
     * -- keeps decoding every remaining entry after one fails, so sibling entries' own problems still
     * surface in the same pass.
     */
    final void readInto(List<MapValue.MapEntry> entries, TsonReadContext ctx, BiConsumer<Object, Object> sink) {
        validateSize(entries.size(), ctx);
        for (MapValue.MapEntry entry : entries) {
            String keySegment = keySegment(entry.key());
            if (entry.key().coreValue() instanceof AbsentValue) {
                ctx.field(keySegment, entry.key()).report(Diagnostic.Code.TYPE_MISMATCH,
                        "'" + name + "': the absent sentinel '_' must not appear as a map key (§2.9)",
                        "a real map key, never the absent sentinel '_'", "_");
                continue;
            }
            Object key = keyParser.read(entry.key(), ctx.field(keySegment, entry.key()));
            Object decodedValue = valueParser.read(entry.value().value(), ctx.field(keySegment, entry.value().value()));
            sink.accept(key, decodedValue);
        }
    }

    private static String keySegment(DataValue key) {
        if (key.coreValue() instanceof TokenValue token) {
            return token.text();
        }
        return String.valueOf(key.coreValue());
    }

    private void validateSize(int size, TsonReadContext ctx) {
        body.minItems().ifPresent(min -> {
            if (BigInteger.valueOf(size).compareTo(min) < 0) {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                        "'" + name + "' has " + size + " entries, fewer than the minimum " + min,
                        "at least " + min + " entries", String.valueOf(size));
            }
        });
        body.maxItems().ifPresent(max -> {
            if (BigInteger.valueOf(size).compareTo(max) > 0) {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                        "'" + name + "' has " + size + " entries, more than the maximum " + max,
                        "at most " + max + " entries", String.valueOf(size));
            }
        });
    }
}
