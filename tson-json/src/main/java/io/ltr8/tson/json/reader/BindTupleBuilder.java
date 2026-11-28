package io.ltr8.tson.json.reader;

import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassTuple;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.base.diagnostics.BindingDiagnostics;
import io.ltr8.tson.base.diagnostics.BindingDiagnostics.Handed;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.schema.meta.EntryDisplayName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bind mode's tuple: an unmodifiable {@code List} of the positions' host values, or -- where a component declares
 * one -- that component's own tuple class ({@code @Tuple}), constructed from every position at once. All-or-nothing,
 * as every bind builder is.
 *
 * <p><b>A named tuple binds by its name, then by the component that holds it.</b> A declaration's name is what a
 * binding map keys on ([TSON-SCHEMA] §8.2), so a named tuple reads into the class the binder names for it; read
 * directly with nothing bound, it raises the {@link MissingBindingException} a record does, a tuple's host form being a
 * declared class as a record's is. A tuple written in place -- {@code [text, int32]} at a field -- has no name a
 * binding map could hold, and reads as a {@code List}. Either way a component declaring a tuple class reads the
 * position again for that class ({@link #forTarget}), each position bound to the class's element.
 */
final class BindTupleBuilder implements TupleBuilder {

    /**
     * Bind mode's tuple reader for a position nothing more specific declares: a named tuple into the class bound to its
     * name, and a tuple written in place into a {@code List}.
     */
    static final ValueReaderFactory FACTORY = (name, definition, context) -> {
        TuplePlan plan = TuplePlan.of(name, definition, context);
        TupleReader natural = new TupleReader(plan, plan.schemaSlots(), new BindTupleBuilder(null));
        if (!context.schema().entries().containsKey(name)) {
            return natural;
        }
        DataClass bound;
        try {
            bound = BindRecordBuilder.boundClass(name, context);
        } catch (MissingBindingException missing) {
            return new TupleReader(plan, plan.schemaSlots(), new BindTupleBuilder(null), missing);
        }
        if (!(bound instanceof DataClassTuple tuple)) {
            throw new BindMismatchException("'" + name + "' is a tuple, and " + bound.typeClass().getName()
                    + ", which is bound to it, is not tuple-shaped");
        }
        String displayName = "'" + EntryDisplayName.of(name, context.schema()) + "'";
        List<String> mismatches = new ArrayList<>();
        JsonTypeReader<?> reader = forTarget(natural, tuple, displayName, mismatches);
        if (!mismatches.isEmpty()) {
            throw new BindMismatchException(displayName + " and " + tuple.typeClass().getName() + " do not agree: "
                    + String.join("; ", mismatches));
        }
        return reader;
    };

    /**
     * {@code tuple} read again for {@code target}, each position's schema reader bound to the target's element at the
     * same index -- never the reader {@code tuple} bound it to, a component's class replacing the one bound to the
     * tuple's name. The target's arity must be the tuple's, and a voidable position cannot reach a primitive element.
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
            if (plan.voidable()[i] && element.isPrimitive()) {
                mismatches.add(what + "'s position " + i + " is voidable, and " + element.getName()
                        + " has no null to hold a void value");
            }
            slots[i] = BindTargets.to(plan.schemaSlots()[i], target.elements()[i].dataClass(),
                    what + "'s position " + i,
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
            if (positions.get(i) == Slots.VOID) {
                positions.set(i, null);
            }
        }
        if (target == null) {
            return Collections.unmodifiableList(new ArrayList<>(positions));
        }
        try {
            return target.constructor().invoke(positions.toArray());
        } catch (Throwable e) {
            ctx.report(BindingDiagnostics.rejectedUnderSchema(target.typeClass(), Handed.POSITIONS, e));
            return null;
        }
    }

    @Override
    public Object refused() {
        return null;
    }
}
