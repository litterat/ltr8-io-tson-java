package io.ltr8.tson.json.writer;

import io.ltr8.tson.json.JsonDataEmitter;
import io.ltr8.tson.json.tree.JsonArray;
import io.ltr8.tson.json.tree.JsonBoolean;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonNumber;
import io.ltr8.tson.json.tree.JsonObject;
import io.ltr8.tson.json.tree.JsonString;
import io.ltr8.tson.json.tree.JsonValue;

/**
 * Writes one {@link JsonValue} into an emitter a caller already owns, and stops -- the engine {@code
 * JsonTreeWriter} is the facade over, and the exact inverse of {@code SchemalessTreeReader}. The split is
 * that reader's: a front door owns the <em>document</em> (the sinks it writes to, the form it writes in)
 * where an engine owns one value and contributes no framing at all.
 *
 * <p><b>Nothing here interprets a value, which is what makes the round trip total.</b> Every one of RFC
 * 8259's six kinds has exactly one spelling and the tree already holds it -- a number keeps the literal it
 * was read from, so [TSON-JSON] §5.3's digits and scale survive ({@code 199.90} is not {@code 199.9}), and a
 * string keeps its content, so the lexer's escape decoding and {@code JsonText}'s quoting are one inverse
 * pair rather than two opinions. A tree read by {@code JsonTreeReader} and written back is the same
 * document, modulo the whitespace §9.3 does not make a value.
 *
 * <p>Stateless, so one instance serves every write.
 */
public final class TreeValueWriter {

    /** Writes {@code value} into {@code out}, contributing no framing of its own. */
    public void write(JsonValue value, JsonDataEmitter out) {
        switch (value) {
            case JsonObject object -> {
                out.beginObject();
                object.members().forEach((name, member) -> {
                    out.member(name);
                    write(member, out);
                });
                out.endObject();
            }
            case JsonArray array -> {
                out.beginArray();
                array.elements().forEach(element -> write(element, out));
                out.endArray();
            }
            case JsonString string -> out.stringValue(string.value());
            case JsonNumber number -> out.numberValue(number.literal());
            case JsonBoolean bool -> out.booleanValue(bool.value());
            case JsonNull ignored -> out.nullValue();
        }
    }
}
