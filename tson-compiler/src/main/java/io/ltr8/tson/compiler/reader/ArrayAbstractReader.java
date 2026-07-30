package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.compiler.ast.AbsentValue;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.ScopedValue;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
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
    final Optional<SourcePosition> schemaPosition;

    ArrayAbstractReader(String name, ArrayBody body, TsonValueReaderResolver resolver, Optional<SourcePosition> schemaPosition) {
        this.name = name;
        this.body = body;
        this.elementParser = resolver.resolve(body.elementType().name());
        this.schemaPosition = schemaPosition;
    }

    /** Returns {@code null} (not a real element list) on a shape mismatch -- see {@link RecordAbstractReader#dataFields} for the identical "caller must stop, not also report every element missing" contract. */
    final List<ScopedValue> elements(DataValue value, TsonReadContext ctx) {
        if (value == null) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected an array for '" + name + "', found no value",
                    "an array", "no value");
            return null;
        }
        CoreValue core = value.coreValue();
        if (core instanceof ArrayValue av) {
            return av.elements();
        }
        ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected an array for '" + name + "', found " + core,
                "an array", String.valueOf(core));
        return null;
    }

    static boolean isAbsent(DataValue value) {
        return value == null || value.coreValue() instanceof AbsentValue;
    }

    /**
     * Validates size, then decodes every element in order, handing each to {@code sink} -- checking
     * {@code unique_items} along the way regardless of how (or whether) {@code sink} itself would
     * otherwise tolerate a duplicate. Keeps decoding every remaining element after one fails (a
     * {@code null} placeholder is handed to {@code sink} for that element, never skipped) so later
     * elements' own {@link TsonReadContext#index} positions stay accurate against the original data.
     */
    final void readInto(List<ScopedValue> elements, TsonReadContext ctx, Consumer<Object> sink) {
        validateSize(elements.size(), ctx);
        Set<Object> seen = body.uniqueItems() ? new LinkedHashSet<>() : null;
        int index = 0;
        for (ScopedValue element : elements) {
            DataValue elementValue = element.value();
            Object decoded = isAbsent(elementValue) ? defaultOrRequire(index, ctx)
                    : elementParser.read(elementValue, ctx.index(index, elementValue));
            if (seen != null && !seen.add(decoded)) {
                ctx.index(index, elementValue).report(Diagnostic.Code.TYPE_MISMATCH,
                        "'" + name + "' requires unique elements, '" + decoded + "' appears more than once",
                        "a value not already present in this array", String.valueOf(decoded));
            }
            sink.accept(decoded);
            index++;
        }
    }

    private Object defaultOrRequire(int index, TsonReadContext ctx) {
        if (body.state() == ElementState.REQUIRED) {
            ctx.index(index, null).report(Diagnostic.Code.FIELD_REQUIRED,
                    "'" + name + "' element [" + index + "] is absent, but elements are required",
                    "a value", "(absent)");
        }
        return null;
    }

    private void validateSize(int size, TsonReadContext ctx) {
        body.minItems().ifPresent(min -> {
            if (BigInteger.valueOf(size).compareTo(min) < 0) {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                        "'" + name + "' has " + size + " elements, fewer than the minimum " + min,
                        "at least " + min + " elements", String.valueOf(size));
            }
        });
        body.maxItems().ifPresent(max -> {
            if (BigInteger.valueOf(size).compareTo(max) > 0) {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                        "'" + name + "' has " + size + " elements, more than the maximum " + max,
                        "at most " + max + " elements", String.valueOf(size));
            }
        });
    }
}
