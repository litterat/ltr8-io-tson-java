package io.ltr8.tson.json;

/**
 * A position in a JSON document: 1-based line, 1-based column counted in Unicode code points, and a
 * 0-based UTF-8 byte offset from the start of the document (after any leading BOM has been stripped).
 *
 * <p><b>The byte offset is the reason this type exists</b> rather than JEP 540's line-and-position
 * pair. [TSON-DATA] §8.1 requires a byte offset in every error report, and [TSON-JSON] §9.4 carries
 * that requirement into this encoding unchanged ("positions in error reports are JSON source
 * positions -- line, column, byte offset"). An offset re-derived from decoded characters is right only
 * while the input is well-formed UTF-8, which is exactly the case where the offset matters most, so
 * {@code JsonLexer} counts it off the bytes it decodes.
 *
 * <p>Distinct from {@code io.ltr8.tson.compiler.Position} deliberately: the JSON encoding is its own
 * stack, and the two are converted at the one boundary where a JSON read reports a TSON diagnostic.
 */
public record JsonPosition(int line, int column, int byteOffset) {

    /** Renders as JEP 540's {@code JsonParseException} does, so a message reads the same in either API. */
    @Override
    public String toString() {
        return "line " + line + ", position " + column;
    }
}
