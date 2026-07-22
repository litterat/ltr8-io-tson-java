package io.ltr8.tson.parser;

import io.ltr8.tson.parser.lexer.Position;

/**
 * Thrown when a document's header contains {@code !!meta}, making it a TSON <em>schema</em>
 * document rather than a data document. A Class 1 (data-format-only) processor does not support
 * schema documents and must report this as a categorized diagnostic, not a generic parse error
 * (§1.5: "A Class 1 processor rejects schema documents with a categorized diagnostic"; §8.1:
 * "MUST report the document as a TSON schema document that this processor does not support").
 *
 * <p>Deliberately not a {@link ParseException}: the input isn't malformed -- it may be a
 * perfectly well-formed schema document per [TSON-SCHEMA] -- this processor simply doesn't
 * implement that layer.
 */
public final class SchemaDocumentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Position position;

    public SchemaDocumentException(Position position) {
        super("this is a TSON schema document (header contains !!meta); "
                + "a Class 1 (data-format-only) processor does not support schema documents"
                + " at line " + position.line() + ", column " + position.column());
        this.position = position;
    }

    public Position position() {
        return position;
    }
}
