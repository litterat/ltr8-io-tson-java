package io.ltr8.tson.json.reader;

import io.ltr8.bind.DataClassTuple;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bind mode's tuple: an unmodifiable {@code List} of the positions' host values, or -- where a component declares
 * one -- that component's own tuple class ({@code @Tuple}), constructed from every position at once. All-or-nothing,
 * as every bind builder is.
 *
 * <p><b>A tuple binds by the component that holds it, not by its schema name.</b> An inline tuple -- {@code
 * [text, int32]} written at a field -- has an entry name the resolver minted, which no binding map can hold
 * ([TSON-SCHEMA] §8.2), so a name-bound tuple would be unreachable exactly where tuples are usually written. Its
 * natural reading is a {@code List}, and a component declaring a tuple class reads the position again for that
 * class ({@link #forTarget}), each position bound to the class's element.
 */
final class BindTupleBuilder implements TupleBuilder {

    /** Bind mode's tuple reader for a position nothing more specific declares: the positions into a {@code List}. */
    static final ValueReaderFactory FACTORY = (name, definition, context) -> {
        TuplePlan plan = TuplePlan.of(name, definition, context);
        return new TupleReader(plan, plan.schemaSlots(), new BindTupleBuilder(null));
    };

    /**
     * {@code tuple} read again for {@code target}, each position bound to the target's element at the same index.
     * The target's arity must be the tuple's, and an optional position cannot reach a primitive element.
     */
    static JsonTypeReader<?> forTarget(TupleReader tuple, DataClassTuple target, String what,
                                       List<String> mismatches) {
        TuplePlan plan = tuple.plan();
        if (target.elements().length != plan.arity()) {
            mismatches.add(what + " has " + plan.arity() + " positions, and " + target.typeClass().getSimpleName()
                    + " has " + target.elements().length);
            return tuple;
        }
        JsonTypeReader<?>[] slots = new JsonTypeReader<?>[plan.arity()];
        for (int i = 0; i < slots.length; i++) {
            Class<?> element = target.elements()[i].dataClass().typeClass();
            if (plan.optional()[i] && element.isPrimitive()) {
                mismatches.add(what + "'s position " + i + " admits absence, and " + element.getName()
                        + " has none to hold it");
            }
            slots[i] = BindTargets.to(tuple.slot(i), target.elements()[i].dataClass(), what + "'s position " + i,
                    target.typeClass().getSimpleName() + "'s element " + i, mismatches);
        }
        return new TupleReader(plan, slots, new BindTupleBuilder(target));
    }

    /** The target's descriptor, or null for the natural {@code List}. */
    private final DataClassTuple target;

    private BindTupleBuilder(DataClassTuple target) {
        this.target = target;
    }

    @Override
    public Object build(JsonReadContext ctx, List<Object> positions, boolean clean) {
        if (!clean) {
            return null;
        }
        for (int i = 0; i < positions.size(); i++) {
            if (positions.get(i) == Slots.ABSENT) {
                positions.set(i, null);
            }
        }
        if (target == null) {
            return Collections.unmodifiableList(new ArrayList<>(positions));
        }
        try {
            return target.constructor().invoke(positions.toArray());
        } catch (Throwable e) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "%s rejected the positions read for it: %s"
                    .formatted(target.typeClass().getSimpleName(), e), "positions "
                    + target.typeClass().getSimpleName() + " accepts", String.valueOf(e.getMessage()));
            return null;
        }
    }

    @Override
    public Object refused() {
        return null;
    }
}
