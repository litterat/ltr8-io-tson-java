package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;

import java.math.BigInteger;

/** The rules both map forms share: an entry's value, the size facets, and a value of the wrong shape. */
final class MapEntries {

    /** How a JSON document spells absence (§7), for the {@code actual} of a rule about an entry value's state. */
    private static final String NULL = "null";

    private MapEntries() {
    }

    /**
     * An entry's value. {@code {K => V?}} admits JSON null as the entry's <b>absent value</b> -- the entry is
     * present, counts toward the size bounds, and carries no value; under {@code {K => V}} a null entry value is a
     * validation error, as with an array element (§7).
     */
    static Object value(MapPlan plan, JsonTypeReader<?> reader, JsonReadContext at, String keySegment) {
        if (at.peek() instanceof JsonEvent.NullValue) {
            at.next();
            if (!plan.optionalValues()) {
                at.report(plan.rules().absentEntryValue(keySegment, NULL));
            }
            return Slots.ABSENT;
        }
        Object value = reader.read(at);
        return value == null ? Slots.REFUSED : value;
    }

    /** §6.5: size facets count entries -- an entry with an absent value is an entry. */
    static void checkSize(MapPlan plan, JsonReadContext ctx, int count) {
        BigInteger size = BigInteger.valueOf(count);
        plan.minItems().filter(min -> size.compareTo(min) < 0)
                .ifPresent(min -> ctx.report(plan.rules().tooFewEntries(min, count)));
        plan.maxItems().filter(max -> size.compareTo(max) > 0)
                .ifPresent(max -> ctx.report(plan.rules().tooManyEntries(max, count)));
    }

    /** The value was not the shape its form takes -- the schema chose the form, so the document is wrong. */
    static void wrongShape(MapPlan plan, JsonReadContext ctx, JsonEvent found) {
        ctx.report(plan.rules().notAMap(JsonAtoms.describe(found)));
        EventSkip.value(ctx, found);
    }
}
