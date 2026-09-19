package io.ltr8.tson.json.reader;

import io.ltr8.bind.DataClassArray;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bind mode's array or set: a {@code List} of the elements' host values, or -- where a component declares one --
 * that component's own collection or array class, built through its descriptor. All-or-nothing, as every bind
 * builder is: an array whose read reported anything builds nothing.
 */
final class BindArrayBuilder implements ArrayBuilder {

    /**
     * Bind mode's array and set reader for a position nothing more specific declares -- a document's root, or
     * an element of an unbound container: the elements at their natural readings, into an unmodifiable {@code List}.
     * A record component declaring its own class reads the position again for that class ({@link #forTarget}).
     */
    static final ValueReaderFactory FACTORY = (name, definition, context) -> {
        ArrayPlan plan = ArrayPlan.of(name, definition, context);
        return new ArrayReader(plan, plan.schemaElement(), new BindArrayBuilder(null));
    };

    /**
     * {@code array} read again for {@code target} -- a component's {@code List<Long>}, {@code long[]} or
     * {@code Set<UUID>} -- its element bound to the target's element. An optional element cannot reach a
     * primitive array, which has nowhere to put the absence.
     */
    static JsonTypeReader<?> forTarget(ArrayReader array, DataClassArray target, String what,
                                       List<String> mismatches) {
        ArrayPlan plan = array.plan();
        if (plan.optionalElements() && target.typeClass().isArray()
                && target.arrayDataClass().typeClass().isPrimitive()) {
            mismatches.add(what + " admits absent elements, and " + target.typeClass().getSimpleName()
                    + " has no absence to hold one");
        }
        JsonTypeReader<?> element = BindTargets.to(array.element(), target.arrayDataClass(),
                what + "'s element", target.typeClass().getSimpleName() + "'s element", mismatches);
        return new ArrayReader(plan, element, new BindArrayBuilder(target));
    }

    /** The target's descriptor, or null for the natural {@code List}. */
    private final DataClassArray target;

    private BindArrayBuilder(DataClassArray target) {
        this.target = target;
    }

    @Override
    public Object build(JsonReadContext ctx, List<Object> elements, boolean clean) {
        if (!clean) {
            return null;
        }
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i) == Slots.ABSENT) {
                elements.set(i, null);
            }
        }
        if (target == null) {
            return Collections.unmodifiableList(new ArrayList<>(elements));
        }
        try {
            Object built = target.constructor().invoke(target.typeClass().isArray() ? elements.size() : 0);
            Object iterator = target.iterator().invoke(built);
            for (Object element : elements) {
                target.put().invoke(built, iterator, element);
            }
            return built;
        } catch (Throwable e) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "%s rejected the elements read for it: %s"
                    .formatted(target.typeClass().getSimpleName(), e), "elements "
                    + target.typeClass().getSimpleName() + " accepts", String.valueOf(e.getMessage()));
            return null;
        }
    }

    @Override
    public Object refused() {
        return null;
    }
}
