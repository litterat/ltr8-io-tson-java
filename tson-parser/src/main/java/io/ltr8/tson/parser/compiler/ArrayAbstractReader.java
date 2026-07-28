package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.TsonValueReader;
import io.ltr8.tson.parser.ast.AbsentValue;
import io.ltr8.tson.parser.ast.ArrayValue;
import io.ltr8.tson.parser.ast.CoreValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.ScopedValue;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ElementState;

import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Everything {@link ArrayDomReader} and {@link ArrayBindReader} share verbatim: resolving the
 * element type's own reader once at construction, unwrapping an array-shaped {@link DataValue} into
 * its element list, and decoding those elements one at a time -- validating {@code min_items}/{@code
 * max_items}, tolerating (or rejecting) an absent element per {@link ElementState}, and rejecting a
 * duplicate *decoded* element when {@code unique_items} says to -- handing each decoded element to a
 * {@link Consumer} rather than assembling a result itself, since how a decoded element gets stored
 * (a plain {@code List.add}, or a {@code tson-bind} {@code DataClassArray}'s own {@code put()}
 * {@link java.lang.invoke.MethodHandle}) differs completely between the two subclasses. Array
 * elements have no default/fixed-value concept at all ({@link ElementState} has only {@code
 * REQUIRED}/{@code OPTIONAL}, unlike a record field's five-member {@code FieldState}), so there's
 * nothing here resembling {@link RecordAbstractReader}'s own precomputed-default machinery.
 *
 * <p>{@code unordered} is deliberately never validated here -- there's nothing to check about a
 * single array value's own ordering in isolation, only meaningful when *comparing* two arrays.
 */
abstract class ArrayAbstractReader<T> implements TsonValueReader<T> {

    final String name;
    final ArrayBody body;
    final TsonValueReader<?> elementParser;

    ArrayAbstractReader(String name, ArrayBody body, ValueReaderResolver resolver) {
        this.name = name;
        this.body = body;
        this.elementParser = resolver.resolve(body.elementType().name());
    }

    final List<ScopedValue> elements(DataValue value) {
        if (value == null) {
            throw new IllegalArgumentException("expected an array for '" + name + "', found no value");
        }
        CoreValue core = value.coreValue();
        if (core instanceof ArrayValue av) {
            return av.elements();
        }
        throw new IllegalArgumentException("expected an array for '" + name + "', found " + core);
    }

    static boolean isAbsent(DataValue value) {
        return value == null || value.coreValue() instanceof AbsentValue;
    }

    /** Validates size, then decodes every element in order, handing each to {@code sink} -- checking {@code unique_items} along the way regardless of how (or whether) {@code sink} itself would otherwise tolerate a duplicate. */
    final void readInto(List<ScopedValue> elements, Consumer<Object> sink) {
        validateSize(elements.size());
        Set<Object> seen = body.uniqueItems() ? new LinkedHashSet<>() : null;
        int index = 0;
        for (ScopedValue element : elements) {
            DataValue elementValue = element.value();
            Object decoded = isAbsent(elementValue) ? defaultOrRequire(index) : elementParser.read(elementValue);
            if (seen != null && !seen.add(decoded)) {
                throw new IllegalArgumentException(
                        "'" + name + "' requires unique elements, '" + decoded + "' appears more than once");
            }
            sink.accept(decoded);
            index++;
        }
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
