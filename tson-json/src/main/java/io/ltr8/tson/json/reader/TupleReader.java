package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * A tuple as a JSON array of exactly its declared length, in every read mode: [TSON-JSON] §6.4. Each position
 * decodes at its own reader; an OPTIONAL position's absent value is null in its slot, and at a REQUIRED position
 * null is a validation error (§7). The mode's {@link TupleBuilder} builds the value once.
 *
 * <p><b>Short or long arrays are validation errors regardless of trailing-optional positions</b> ([TSON-SCHEMA]
 * §5.3). A tuple's arity is part of its type -- an optional position means it may hold no value, never that it
 * may be missing -- so the count is judged once the array has closed.
 */
final class TupleReader implements JsonTypeReader<Object> {

    /** How a JSON document spells absence (§7), for the {@code actual} of a rule about a position's state. */
    private static final String NULL = "null";

    private final TuplePlan plan;
    private final JsonTypeReader<?>[] slots;
    private final TupleBuilder builder;

    TupleReader(TuplePlan plan, JsonTypeReader<?>[] slots, TupleBuilder builder) {
        this.plan = plan;
        this.slots = slots.clone();
        this.builder = builder;
    }

    TuplePlan plan() {
        return plan;
    }

    JsonTypeReader<?> slot(int position) {
        return slots[position];
    }

    @Override
    public Object read(JsonReadContext ctx) {
        ctx = ctx.underDeclaration(plan.schemaLocation());
        JsonEvent first = ctx.next();
        if (!(first instanceof JsonEvent.ArrayStart)) {
            ctx.report(plan.rules().notATuple(JsonAtoms.describe(first)));
            EventSkip.value(ctx, first);
            return builder.refused();
        }
        int reportedBefore = ctx.reported();
        List<Object> positions = new ArrayList<>(slots.length);
        while (!(ctx.peek() instanceof JsonEvent.ArrayEnd)) {
            int position = positions.size();
            JsonReadContext at = ctx.index(position);
            if (position >= slots.length) {
                // Past the declared arity: reported once for the whole overflow below, the rest discarded, since
                // there is no position for any of them to be read at.
                EventSkip.nextValue(at);
                positions.add(Slots.REFUSED);
                continue;
            }
            positions.add(readPosition(at, position));
        }
        ctx.next();   // ArrayEnd
        if (positions.size() > slots.length) {
            ctx.report(plan.rules().tooManyElements());
        } else if (positions.size() < slots.length) {
            ctx.report(plan.rules().tooFewElements(positions.size()));
        }
        return builder.build(ctx, positions, ctx.reported() == reportedBefore);
    }

    private Object readPosition(JsonReadContext at, int position) {
        if (at.peek() instanceof JsonEvent.NullValue) {
            at.next();
            if (!plan.optional()[position]) {
                at.report(plan.rules().absentPosition(position, NULL));
            }
            return Slots.ABSENT;
        }
        Object value = slots[position].read(at);
        return value == null ? Slots.REFUSED : value;
    }
}
