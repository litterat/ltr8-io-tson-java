package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.unicode.Nfc;
import io.ltr8.tson.json.tree.JsonArray;
import io.ltr8.tson.json.tree.JsonNumber;
import io.ltr8.tson.json.tree.JsonObject;
import io.ltr8.tson.json.tree.JsonString;
import io.ltr8.tson.json.tree.JsonValue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;

/**
 * A decoded value reduced to what it <em>is</em>, so two spellings of one value compare equal.
 * [TSON-SCHEMA] §5.5 fixes each family's value space, and this applies it: what a family preserves is not
 * always part of what it is.
 *
 * <p>Five families have a spelling their host type keeps, and each is folded here. Text compares under NFC
 * ([TSON-DATA] §2.5); an octet string compares by content, where {@code byte[]} would compare by reference at
 * every site that forgot; an instant compares as an instant, the offset being a spelling ([TSON-JSON] §5.6);
 * and an exact number compares by value, since §5.3 makes {@code 199.90} and {@code 199.9} one {@code number}
 * while {@code BigDecimal.equals} separates them by scale.
 *
 * <p>The peer of {@code tson-compiler}'s {@code ValueIdentity}, and one whose two copies must agree: a
 * duplicate key and a contradicted FIXED value are the same question in both encodings.
 */
final class JsonValueIdentity {

    private JsonValueIdentity() {
    }

    static Object of(Object decoded) {
        return switch (decoded) {
            case JsonValue node -> ofNode(node);
            case String text -> Nfc.of(text);
            case byte[] octets -> ByteBuffer.wrap(octets);
            case BigDecimal number -> number.stripTrailingZeros();
            case OffsetDateTime moment -> moment.toInstant();
            case OffsetTime clock -> clock.withOffsetSameInstant(ZoneOffset.UTC).toLocalTime();
            default -> decoded;
        };
    }

    /**
     * A decoded tree reduced the same way, for a compound map key ([TSON-JSON] §6.5's pairs form): a
     * {@code JsonNumber} keeps its literal, so {@code 1} and {@code 1.0} would compare unequal inside a key
     * that §5.3 makes one value. Reduced recursively, since the key may be a record or an array of them.
     *
     * <p>Member order is dropped with it -- §6.1.6 gives it no meaning, so two objects differing only in
     * order are one key.
     */
    private static Object ofNode(JsonValue node) {
        return switch (node) {
            case JsonNumber number -> number.toBigDecimal().stripTrailingZeros();
            case JsonString string -> Nfc.of(string.value());
            case JsonArray array -> array.elements().stream().map(JsonValueIdentity::ofNode).toList();
            case JsonObject object -> {
                Map<Object, Object> members = new LinkedHashMap<>();
                object.members().forEach((key, value) -> members.put(Nfc.of(key), ofNode(value)));
                yield Map.copyOf(members);
            }
            default -> node;
        };
    }
}
