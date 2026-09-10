package io.ltr8.tson.compiler;

import java.util.Optional;

/**
 * A TSON document's header directives ([TSON-DATA] §2.2) -- {@code !!id} plus the one of {@code !!schema} /
 * {@code !!meta} the document carries, the pair that says what a document is before any of its value is
 * read.
 *
 * <p><b>Both ends of the library share this type.</b> {@link TsonDocumentPeek} reads one off source text and
 * {@code TsonObjectWriter#describing}/{@code TsonTreeWriter#describing} build one to emit. §2.2 makes
 * {@code !!id} the first line when present, and {@link #emit} is the one place that knows it. {@link #NONE}
 * is every writer's default -- a bare value, which is what this library has always written and what every
 * existing consumer of its output expects.
 *
 * <p><b>A pure value, with no way to obtain one on it.</b> Reading a header means running the lexer over a
 * document, which is {@code TsonDataStream}'s job and reaches this type only as a projection of the
 * {@code DocumentStart} it already emits -- so the reading lives with the stream's own front door
 * ({@link TsonDocumentPeek}) and this stays what a header <em>is</em>. A static here would have made the
 * value model the place a document gets parsed.
 *
 * <p>A writer only ever produces a <em>data</em> document, so {@link #meta()} is empty on every header a
 * writer holds; it is populated only by a peek, where it is the whole answer to "is this a schema
 * document?" ([TSON-SCHEMA] §12.1 requires exactly one {@code !!meta}, so its presence decides -- {@link
 * #isSchemaDocument()}).
 *
 * @param id     the document's own identity, or empty
 * @param schema the schema governing the value that follows ({@code !!schema}), or empty
 * @param meta   the meta-schema governing a <em>schema</em> document's declarations ({@code !!meta}), or empty
 */
public record TsonDocumentHeader(Optional<String> id, Optional<String> schema, Optional<String> meta) {

    /** No directives: the writer emits a value and nothing else, and a peeked document declared none. */
    public static final TsonDocumentHeader NONE =
            new TsonDocumentHeader(Optional.empty(), Optional.empty(), Optional.empty());

    /** Whether this is a <em>schema</em> document -- [TSON-SCHEMA] §12.1's {@code !!meta} decides. */
    public boolean isSchemaDocument() {
        return meta.isPresent();
    }

    TsonDocumentHeader describing(String schemaUri) {
        return new TsonDocumentHeader(id, Optional.of(schemaUri), meta);
    }

    TsonDocumentHeader identifiedBy(String documentId) {
        return new TsonDocumentHeader(Optional.of(documentId), schema, meta);
    }

    /** Writes the directives this header carries, {@code !!id} first (§2.2 fixes the order). */
    void emit(TsonDataEmitter out) {
        id.ifPresent(out::documentId);
        schema.ifPresent(out::schemaRef);
    }
}
