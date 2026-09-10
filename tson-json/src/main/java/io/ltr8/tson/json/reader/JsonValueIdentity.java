package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.unicode.Nfc;

import java.math.BigDecimal;
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
            case String text -> Nfc.of(text);
            case byte[] octets -> ByteBuffer.wrap(octets);
            case BigDecimal number -> number.stripTrailingZeros();
            case OffsetDateTime moment -> moment.toInstant();
            case OffsetTime clock -> clock.withOffsetSameInstant(ZoneOffset.UTC).toLocalTime();
            default -> decoded;
        };
    }
}
