package io.ltr8.tson.compiler.compiler;

import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.ast.AbsentValue;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.ScopedValue;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything {@link TupleDomReader} and {@link TupleBindReader} share verbatim: resolving every
 * position's own reader once at construction, unwrapping a tuple's own array-shaped {@link
 * DataValue} (never {@link io.ltr8.tson.compiler.ast.EmptyBrace} -- a tuple is array-shaped on the
 * wire, not record-shaped, matching {@code TsonMapperReader.toTuple}'s own note) and checking its
 * arity against the fixed number of positions, and decoding every position into a single {@code
 * Object[]} in slot order.
 *
 * <p>Each position carries its own type *and* its own {@link ElementState} (§5.3) -- unlike {@link
 * ArrayAbstractReader}, where every element shares one type/state -- so absent-position handling
 * stays per-slot here rather than shared with arrays; the logic is analogous, not identical, so it's
 * duplicated rather than forced through one shared method (matching how {@code isAbsent} is
 * duplicated, not shared, across every structural kind in this package).
 *
 * <p>Arity is fixed and exact, unlike {@link ArrayAbstractReader}/{@link MapAbstractReader}'s {@code
 * min_items}/{@code max_items} range -- a tuple's own arity isn't a range to begin with, so there's
 * nothing resembling their size validation here.
 */
abstract class TupleAbstractReader<T> implements TsonValueReader<T> {

    record CompiledSlot(TupleElement schema, TsonValueReader<?> parser) {
    }

    final String name;
    final List<CompiledSlot> slots;

    TupleAbstractReader(String name, TupleBody body, ValueReaderResolver resolver) {
        this.name = name;
        List<CompiledSlot> slots = new ArrayList<>(body.elements().size());
        for (TupleElement element : body.elements()) {
            slots.add(new CompiledSlot(element, resolver.resolve(element.elementType().name())));
        }
        this.slots = slots;
    }

    final List<ScopedValue> elements(DataValue value) {
        if (value == null) {
            throw new IllegalArgumentException("expected a tuple for '" + name + "', found no value");
        }
        CoreValue core = value.coreValue();
        if (!(core instanceof ArrayValue av)) {
            throw new IllegalArgumentException("expected a tuple (array-shaped) for '" + name + "', found " + core);
        }
        List<ScopedValue> elements = av.elements();
        if (elements.size() != slots.size()) {
            throw new IllegalArgumentException("'" + name + "' has " + slots.size() + " positions, found "
                    + elements.size() + " elements");
        }
        return elements;
    }

    final Object[] decode(List<ScopedValue> elements) {
        Object[] result = new Object[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            CompiledSlot slot = slots.get(i);
            DataValue elementValue = elements.get(i).value();
            result[i] = isAbsent(elementValue) ? defaultOrRequire(slot, i) : slot.parser().read(elementValue);
        }
        return result;
    }

    private static boolean isAbsent(DataValue value) {
        return value == null || value.coreValue() instanceof AbsentValue;
    }

    private Object defaultOrRequire(CompiledSlot slot, int index) {
        if (slot.schema().state() == ElementState.REQUIRED) {
            throw new IllegalArgumentException(
                    "'" + name + "' position [" + index + "] is absent, but this position is required");
        }
        return null;
    }
}
