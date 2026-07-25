package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.ast.AbsentValue;
import io.ltr8.tson.parser.ast.ArrayValue;
import io.ltr8.tson.parser.ast.CoreValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.ScopedValue;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.RecordField;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The {@link TsonParserFactory} for meta-kernel's {@code array} constructor (§5.3), and the {@link
 * TsonTypeParser} it builds -- same shape as {@link RecordParser} (DOM-mode, {@code List<Object>}
 * rather than {@code Map<String, Object>}), and the highest-leverage composite to have: almost
 * every real synthesized entry a materialized schema produces is an {@code array<X>} application
 * (§5.3's {@code [X]}/{@code [X]?} field-type sugar), so nothing resembling a real document reads
 * correctly without this.
 *
 * <p><b>{@code element_type}'s own parser is resolved once, eagerly, at compile time</b> -- unlike
 * {@link VariantParser}'s subtypes (alternatives, only one of which applies to any given value), an
 * array's element type is unconditionally needed for every element on every read, the same
 * reasoning that already justifies {@link RecordParser} resolving all of its own fields eagerly.
 *
 * <p><b>Validates every constraint {@link ArrayBody} actually carries</b> -- not just structural
 * shape. {@code min_items}/{@code max_items} bound the element count; {@code unique_items} checks
 * for duplicate *decoded* elements (via {@code equals()} on whatever {@code element_type}'s own
 * parser produces); {@code state} governs whether the absent sentinel {@code _} is tolerated in
 * element position (mirroring {@link RecordField#state}'s REQUIRED/OPTIONAL treatment one level
 * up, via {@link ElementState}'s own two-member enum). This is exactly the kind of validation a
 * schemaless reader can't do -- the whole point of a schema-backed one.
 *
 * <p><b>{@code unordered} is deliberately never checked</b> -- there's nothing to validate about a
 * single array value's own ordering in isolation; {@code unordered} only has meaning when
 * *comparing* two arrays (e.g. {@code set}'s own equality), which reading one value at a time
 * never does. Not a gap, just out of scope for what a single {@code read()} call can mean.
 */
final class ArrayParser implements TsonTypeParser<List<Object>> {

    static final TsonParserFactory FACTORY = (name, definition, ctx) -> {
        ArrayBody body = (ArrayBody) definition.body();
        TsonTypeParser<?> elementParser = ctx.resolve(body.elementType().name());
        return new ArrayParser(name, body, elementParser);
    };

    private final String name;
    private final ArrayBody body;
    private final TsonTypeParser<?> elementParser;

    private ArrayParser(String name, ArrayBody body, TsonTypeParser<?> elementParser) {
        this.name = name;
        this.body = body;
        this.elementParser = elementParser;
    }

    @Override
    public List<Object> read(DataValue value) {
        if (value == null) {
            throw new IllegalArgumentException("expected an array for '" + name + "', found no value");
        }
        CoreValue core = value.coreValue();
        if (!(core instanceof ArrayValue av)) {
            throw new IllegalArgumentException("expected an array for '" + name + "', found " + core);
        }
        List<ScopedValue> elements = av.elements();
        validateSize(elements.size());

        List<Object> result = new ArrayList<>(elements.size());
        Set<Object> seen = body.uniqueItems() ? new LinkedHashSet<>() : null;
        int index = 0;
        for (ScopedValue element : elements) {
            DataValue elementValue = element.value();
            Object decoded = isAbsent(elementValue) ? defaultOrRequire(index) : elementParser.read(elementValue);
            if (seen != null && !seen.add(decoded)) {
                throw new IllegalArgumentException(
                        "'" + name + "' requires unique elements, '" + decoded + "' appears more than once");
            }
            result.add(decoded);
            index++;
        }
        return result;
    }

    private static boolean isAbsent(DataValue value) {
        return value == null || value.coreValue() instanceof AbsentValue;
    }

    private Object defaultOrRequire(int index) {
        if (body.state() == ElementState.REQUIRED) {
            throw new IllegalArgumentException(
                    "'" + name + "' element [" + index + "] is absent, but elements are required");
        }
        return null;
    }

    private void validateSize(int size) {
        body.minItems().ifPresent(min -> {
            if (BigInteger.valueOf(size).compareTo(min) < 0) {
                throw new IllegalArgumentException(
                        "'" + name + "' has " + size + " elements, fewer than the minimum " + min);
            }
        });
        body.maxItems().ifPresent(max -> {
            if (BigInteger.valueOf(size).compareTo(max) > 0) {
                throw new IllegalArgumentException(
                        "'" + name + "' has " + size + " elements, more than the maximum " + max);
            }
        });
    }
}
