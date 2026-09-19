package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [TSON-JSON] §6.5's <b>pairs form</b>, in every read mode: a map whose key type is a record, tuple, array, map or
 * choice -- the compound keys [TSON-DATA] §2.6 admits, which a JSON object's string-only member names cannot spell.
 * The map is a JSON array of two-element arrays, which is the second of §8.3's class-stability leaks. The mode's
 * {@link MapBuilder} builds the value once.
 *
 * <p>A compound key compares as a value too, through {@link ValueIdentity}'s recursive reduction -- member order
 * carries no meaning (§6.1.6), and a {@code JsonNumber} keeps its literal, so two keys §5.3 makes one value would
 * otherwise compare unequal.
 */
final class MapPairsReader implements JsonTypeReader<Object> {

    private final MapPlan plan;
    private final JsonTypeReader<?> key;
    private final JsonTypeReader<?> value;
    private final MapBuilder builder;

    MapPairsReader(MapPlan plan, JsonTypeReader<?> key, JsonTypeReader<?> value, MapBuilder builder) {
        this.plan = plan;
        this.key = key;
        this.value = value;
        this.builder = builder;
    }

    MapPlan plan() {
        return plan;
    }

    JsonTypeReader<?> key() {
        return key;
    }

    JsonTypeReader<?> value() {
        return value;
    }

    @Override
    public Object read(JsonReadContext ctx) {
        ctx = ctx.underDeclaration(plan.schemaLocation());
        JsonEvent first = ctx.next();
        if (!(first instanceof JsonEvent.ArrayStart)) {
            MapEntries.wrongShape(plan, ctx, first);
            return builder.refused();
        }
        int reportedBefore = ctx.reported();
        List<Object> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        Map<Object, Integer> byIdentity = new HashMap<>();
        int count = 0;
        while (!(ctx.peek() instanceof JsonEvent.ArrayEnd)) {
            JsonReadContext at = ctx.index(count);
            readPair(at, count++, byIdentity, keys, values);
        }
        ctx.next();   // ArrayEnd
        MapEntries.checkSize(plan, ctx, count);
        return builder.build(ctx, null, keys, values, ctx.reported() == reportedBefore);
    }

    /** One {@code [k, v]}. §6.5: an element that is not a two-element array is a validation error, and left out. */
    private void readPair(JsonReadContext at, int index, Map<Object, Integer> byIdentity, List<Object> keys,
                          List<Object> values) {
        JsonEvent opening = at.next();
        if (!(opening instanceof JsonEvent.ArrayStart)) {
            at.report(Diagnostic.Code.TYPE_MISMATCH,
                    "'%s' is in pairs form, whose every element is a two-element array, and this is %s"
                            .formatted(plan.displayName(), JsonAtoms.describe(opening)),
                    "a two-element array", JsonAtoms.describe(opening));
            EventSkip.value(at, opening);
            return;
        }
        if (at.peek() instanceof JsonEvent.ArrayEnd) {
            at.next();
            at.report(Diagnostic.Code.WRONG_ARITY, "'%s' is in pairs form and this entry has no key"
                    .formatted(plan.displayName()), "a two-element array", "an empty array");
            return;
        }
        int before = at.reported();
        Object decodedKey = key.read(at.index(0));
        if (at.peek() instanceof JsonEvent.ArrayEnd) {
            at.next();
            at.report(Diagnostic.Code.WRONG_ARITY, "'%s' is in pairs form and this entry has no value"
                    .formatted(plan.displayName()), "a two-element array", "a one-element array");
            return;
        }
        Object entry = MapEntries.value(plan, value, at.index(1), String.valueOf(index));
        if (decodedKey != null && at.reported() == before
                && byIdentity.putIfAbsent(ValueIdentity.of(decodedKey), index) != null) {
            at.report(plan.rules().duplicateKey(String.valueOf(decodedKey)));
        }
        if (!(at.peek() instanceof JsonEvent.ArrayEnd)) {
            // More than two elements. One diagnostic for the entry, then the rest discarded: there is no position
            // for any of them to be read at, and one malformed entry is one problem.
            at.report(Diagnostic.Code.WRONG_ARITY, "'%s' is in pairs form and this entry has more than a key and "
                    .formatted(plan.displayName()) + "a value", "a two-element array", "a longer array");
            while (!(at.peek() instanceof JsonEvent.ArrayEnd)) {
                EventSkip.nextValue(at);
            }
        }
        at.next();   // the pair's own ArrayEnd
        keys.add(decodedKey == null ? Slots.REFUSED : decodedKey);
        values.add(entry);
    }
}
