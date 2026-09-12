package io.ltr8.tson.json.reader;

import io.ltr8.tson.atom.AtomRefusal;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.tree.JsonObject;
import io.ltr8.tson.json.tree.JsonValue;
import io.ltr8.tson.schema.meta.MapBody;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [TSON-JSON] §6.5's <b>object form</b>: a map whose key type is denoted by a single scalar token, so the map
 * is a JSON object and each member name is a <b>key token</b> whose string content faces {@code K}'s own
 * parsing contract, exactly as §5.1 hands value strings. {@code {"2026-07-01": 12.5}} under
 * {@code {date => number}} carries a date key.
 *
 * <p><b>Keys compare as values, not as spellings.</b> Identity under a declared key type is over its value
 * space ([TSON-SCHEMA] §5.5), so {@code "1"} and {@code "1.0"} under a {@code number} key are one key and two
 * spellings of one octet string under a {@code bytes} key are one key. The repeat is the §7.7 error, and the
 * entry it would have added lands under the first spelling with the later value -- one key, one entry.
 */
final class TreeMapObjectReader extends TreeMapReader {

    private final AtomType<?> keyParser;

    TreeMapObjectReader(String name, MapBody body, AtomType<?> keyParser, JsonTypeReader<?> value,
                        JsonSchemaLocation schemaLocation) {
        super(name, body, value, schemaLocation);
        this.keyParser = keyParser;
    }

    @Override
    JsonValue readEntries(JsonReadContext ctx) {
        JsonEvent first = ctx.next();
        if (!(first instanceof JsonEvent.ObjectStart)) {
            return wrongShape(ctx, first);
        }
        Map<String, JsonValue> entries = new LinkedHashMap<>();
        Map<Object, String> byIdentity = new HashMap<>();
        // Counted separately from `entries`, which drops a member whose key the contract refused: the size
        // facets judge what the document stated, and a member nothing could file is still an entry it wrote.
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
            JsonValue entry = entryValue(at, member.name());
            if (key == null) {
                // The contract refused this member name, reported already, and there is no key to file the
                // entry under. Leaving it out of `byIdentity` also stops a second undecodable name being
                // reported again as a repeat of the first.
                continue;
            }
            String slot = byIdentity.putIfAbsent(ValueIdentity.of(key), member.name());
            if (slot != null) {
                at.report(rules.duplicateKey(member.name()));
            }
            entries.put(slot != null ? slot : member.name(), entry);
        }
        checkSize(ctx, count);
        return new JsonObject(entries);
    }

    /** §6.5: the member name's string content faces {@code K}'s contract. Null where the contract refused it. */
    private Object decodeKey(JsonReadContext at, String memberName) {
        try {
            return keyParser.read(memberName);
        } catch (AtomTypeException e) {
            AtomRefusal refusal = AtomRefusal.of(e, memberName, Object.class).named(name + " key");
            at.report(refusal.code(), refusal.message(), refusal.expected(), refusal.actual());
            return null;
        }
    }
}
