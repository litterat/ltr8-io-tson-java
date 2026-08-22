package io.ltr8.tson.compiler;

import java.util.Optional;

/**
 * The header directives a writer emits ahead of a document's value -- {@code !!id} and {@code !!schema}
 * ([TSON-DATA] §2.2), the two of the four names that belong to a <em>data</em> document.
 *
 * <p>Shared by {@link TsonObjectWriter} and {@link TsonTreeWriter} so the two agree on what a header is and
 * on the order it goes in: §2.2 makes {@code !!id} the first line when present, and {@link #emit} is the one
 * place that knows it. {@link #NONE} is every writer's default -- a bare value, which is what this library
 * has always written and what every existing consumer of its output expects.
 *
 * @param id     the document's own identity, or empty
 * @param schema the schema governing the value that follows, or empty
 */
record DocumentHeader(Optional<String> id, Optional<String> schema) {

    /** No directives: the writer emits a value and nothing else. */
    static final DocumentHeader NONE = new DocumentHeader(Optional.empty(), Optional.empty());

    DocumentHeader describing(String schemaUri) {
        return new DocumentHeader(id, Optional.of(schemaUri));
    }

    DocumentHeader identifiedBy(String documentId) {
        return new DocumentHeader(Optional.of(documentId), schema);
    }

    /** Writes what this header holds, in §2.2's order. A no-op for {@link #NONE}. */
    void emit(TsonDataEmitter out) {
        id.ifPresent(out::documentId);
        schema.ifPresent(out::schemaRef);
    }
}
