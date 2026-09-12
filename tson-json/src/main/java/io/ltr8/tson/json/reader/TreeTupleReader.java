package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.diagnostics.TupleDiagnostics;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.tree.JsonArray;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonValue;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;

import java.util.ArrayList;
import java.util.List;

/**
 * A tuple as a JSON array of exactly its declared length: [TSON-JSON] §6.4. Each slot decodes at its own
 * position's element type; an OPTIONAL position's absent value is null in its slot, and at a REQUIRED
 * position null is a validation error (§7).
 *
 * <p><b>Short or long arrays are validation errors regardless of trailing-optional positions</b>
 * ([TSON-SCHEMA] §5.3). A tuple's arity is part of its type -- an optional slot means the slot may hold no
 * value, never that it may be missing -- so this counts before it judges anything else.
 */
final class TreeTupleReader implements JsonTypeReader<JsonValue> {

    static final ValueReaderFactory FACTORY = (name, definition, context) -> {
        TupleBody body = (TupleBody) definition.body();
        List<JsonTypeReader<?>> slots = new ArrayList<>(body.elements().size());
        for (TupleElement element : body.elements()) {
            slots.add(context.readers().resolve(element.elementType().name()));
        }
        return new TreeTupleReader(name, body, slots, context.locationOf(name, definition));
    };

    private final String name;
    private final TupleBody body;
    private final List<JsonTypeReader<?>> slots;
    private final JsonSchemaLocation schemaLocation;

    /** This tuple's rules, shared with the TSON reader ([TSON-JSON] §9.4). */
    private final TupleDiagnostics rules;

    private TreeTupleReader(String name, TupleBody body, List<JsonTypeReader<?>> slots,
                            JsonSchemaLocation schemaLocation) {
        this.name = name;
        this.body = body;
        this.slots = List.copyOf(slots);
        this.schemaLocation = schemaLocation;
        this.rules = new TupleDiagnostics(name, slots.size());
    }

    @Override
    public JsonValue read(JsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        JsonEvent first = ctx.next();
        if (!(first instanceof JsonEvent.ArrayStart)) {
            ctx.report(rules.notATuple(JsonAtoms.describe(first)));
            EventSkip.value(ctx, first);
            return JsonNull.INSTANCE;
        }
        List<JsonValue> elements = new ArrayList<>(slots.size());
        while (!(ctx.peek() instanceof JsonEvent.ArrayEnd)) {
            int slot = elements.size();
            JsonReadContext at = ctx.index(slot);
            if (slot >= slots.size()) {
                // Past the declared arity: report once for the whole overflow, then discard the rest, since
                // there is no position for any of them to be read at.
                EventSkip.nextValue(at);
                elements.add(JsonNull.INSTANCE);
                continue;
            }
            elements.add(readSlot(at, slot));
        }
        ctx.next();   // ArrayEnd
        if (elements.size() > slots.size()) {
            ctx.report(rules.tooManyElements());
        } else if (elements.size() < slots.size()) {
            ctx.report(rules.tooFewElements(elements.size()));
        }
        return new JsonArray(elements);
    }

    /** How a JSON document spells absence (§7), for the `actual` of a rule about a position's state. */
    private static final String ABSENT = "null";

    private JsonValue readSlot(JsonReadContext at, int slot) {
        if (at.peek() instanceof JsonEvent.NullValue) {
            at.next();
            if (body.elements().get(slot).state() == ElementState.REQUIRED) {
                at.report(rules.absentPosition(slot, ABSENT));
            }
            return JsonNull.INSTANCE;
        }
        return (JsonValue) slots.get(slot).read(at);
    }
}
