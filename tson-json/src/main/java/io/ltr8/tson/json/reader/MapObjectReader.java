package io.ltr8.tson.json.reader;

import io.ltr8.bind.DataClassBridge;
import io.ltr8.tson.atom.AtomRefusal;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.stream.JsonEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [TSON-JSON] §6.5's <b>object form</b>, in every read mode: a map whose key type is denoted by a single scalar
 * token, so the map is a JSON object and each member name is a <b>key token</b> whose string content faces
 * {@code K}'s own parsing contract, exactly as §5.1 hands value strings. {@code {"2026-07-01": 12.5}} under
 * {@code {date => number}} carries a date key. The mode's {@link MapBuilder} builds the value once.
 *
 * <p><b>Keys compare as values, not as spellings.</b> Identity under a declared key type is over its value space
 * ([TSON-SCHEMA] §5.5), so {@code "1"} and {@code "1.0"} under a {@code number} key are one key. The repeat is the
 * §7.7 error, and its value lands under the first spelling -- one key, one entry.
 */
final class MapObjectReader implements JsonTypeReader<Object> {

    private final MapPlan plan;
    private final AtomType<?> keyParser;

    /** What makes the key's class of the parsed key, where its target reaches it through a bridge; else null. */
    private final DataClassBridge keyBridge;

    private final JsonTypeReader<?> value;
    private final MapBuilder builder;

    MapObjectReader(MapPlan plan, AtomType<?> keyParser, DataClassBridge keyBridge, JsonTypeReader<?> value,
                    MapBuilder builder) {
        this.plan = plan;
        this.keyParser = keyParser;
        this.keyBridge = keyBridge;
        this.value = value;
        this.builder = builder;
    }

    MapPlan plan() {
        return plan;
    }

    JsonTypeReader<?> value() {
        return value;
    }

    @Override
    public Object read(JsonReadContext ctx) {
        ctx = ctx.underDeclaration(plan.schemaLocation());
        JsonEvent first = ctx.next();
        if (!(first instanceof JsonEvent.ObjectStart)) {
            MapEntries.wrongShape(plan, ctx, first);
            return builder.refused();
        }
        int reportedBefore = ctx.reported();
        List<String> names = new ArrayList<>();
        List<Object> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        Map<Object, Integer> byIdentity = new HashMap<>();
        // Counted apart from the entries, which leave out a member whose key the contract refused: the size facets
        // judge what the document stated, and a member nothing could file is still an entry it wrote.
        int count = 0;
        while (true) {
            JsonEvent event = ctx.next();
            if (event instanceof JsonEvent.ObjectEnd) {
                break;
            }
            if (!(event instanceof JsonEvent.MemberName member)) {
                throw new IllegalStateException("a member name or '}' was due and the stream produced " + event);
            }
            count++;
            JsonReadContext at = ctx.field(member.name());
            Object key = decodeKey(at, member.name());
            Object entry = MapEntries.value(plan, value, at, member.name());
            if (key == null) {
                // The contract refused this member name, reported already, and there is no key to file the entry
                // under. Leaving it out of `byIdentity` also stops a second undecodable name being reported again
                // as a repeat of the first.
                continue;
            }
            Integer slot = byIdentity.putIfAbsent(ValueIdentity.of(key), keys.size());
            if (slot != null) {
                at.report(plan.rules().duplicateKey(member.name()));
                values.set(slot, entry);
                continue;
            }
            names.add(member.name());
            keys.add(key);
            values.add(entry);
        }
        MapEntries.checkSize(plan, ctx, count);
        return builder.build(ctx, names, keys, values, ctx.reported() == reportedBefore);
    }

    /** §6.5: the member name's string content faces {@code K}'s contract. Null where the contract refused it. */
    private Object decodeKey(JsonReadContext at, String memberName) {
        Object key;
        try {
            key = keyParser.read(memberName);
        } catch (AtomTypeException e) {
            AtomRefusal refusal = AtomRefusal.of(e, memberName, Object.class).named(plan.displayName() + " key");
            at.report(refusal.code(), refusal.message(), refusal.expected(), refusal.actual());
            return null;
        }
        if (keyBridge == null) {
            return key;
        }
        try {
            return keyBridge.toObject().invoke(key);
        } catch (Throwable e) {
            at.report(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, "'%s' is not a %s key: %s"
                    .formatted(memberName, plan.displayName(), e.getMessage()), "a key its class accepts",
                    memberName);
            return null;
        }
    }
}
