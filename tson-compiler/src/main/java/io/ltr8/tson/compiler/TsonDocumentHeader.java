package io.ltr8.tson.compiler;

import java.util.List;
import java.util.Optional;

/**
 * A TSON document's header directives ([TSON-DATA] §2.2) -- its {@code !!id} and its {@code !!import}s, in header
 * order.
 *
 * <p><b>Both ends of the library share this type.</b> {@link TsonDocumentPeek} reads one off source text and
 * {@code TsonObjectWriter#describing}/{@code TsonTreeWriter#describing} build one to emit. §2.2 makes
 * {@code !!id} the first line when present, and {@link #emit} is the one place that knows it. {@link #NONE}
 * is every writer's default -- a bare value, which is what a writer is usually asked for; a caller wanting
 * a self-describing document says so.
 *
 * <p><b>A pure value, with no way to obtain one on it.</b> Reading a header means running the lexer over a
 * document, which is {@code TsonDataStream}'s job and reaches this type only as a projection of the
 * {@code DocumentStart} it already emits -- so the reading lives with the stream's own front door
 * ({@link TsonDocumentPeek}) and this stays what a header <em>is</em>.
 *
 * <p><b>A header does not say whether a document is a schema.</b> The root's {@code !schema} tag does (§2.2), and
 * {@link TsonDocumentPeek#isSchemaDocument()} reads it.
 *
 * @param id      the document's own identity, or empty
 * @param imports the schemas the document imports ({@code !!import}), in header order
 */
public record TsonDocumentHeader(Optional<String> id, List<String> imports) {

    /** No directives: the writer emits a value and nothing else, and a peeked document declared none. */
    public static final TsonDocumentHeader NONE = new TsonDocumentHeader(Optional.empty(), List.of());

    public TsonDocumentHeader {
        imports = List.copyOf(imports);
    }

    /** This header importing {@code schemaUri} as well, after the imports it already has. */
    TsonDocumentHeader describing(String schemaUri) {
        List<String> more = new java.util.ArrayList<>(imports);
        more.add(schemaUri);
        return new TsonDocumentHeader(id, more);
    }

    /** This header with {@code document}'s imports in place of its own: a document's own header wins. */
    TsonDocumentHeader importing(List<String> documentImports) {
        return new TsonDocumentHeader(id, documentImports);
    }

    TsonDocumentHeader identifiedBy(String documentId) {
        return new TsonDocumentHeader(Optional.of(documentId), imports);
    }

    /** Writes the directives this header carries, {@code !!id} first (§2.2 fixes the order). */
    void emit(TsonDataEmitter out) {
        id.ifPresent(out::documentId);
        imports.forEach(out::importRef);
    }
}
