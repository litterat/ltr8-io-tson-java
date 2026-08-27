package io.ltr8.tson.tree;

import java.util.Optional;

/**
 * A whole data document as a tree: its header directives ([TSON-DATA] §2.2) and the single value they
 * govern. The tree-model counterpart of the parser's own {@code ast.Document}, standing to {@link TsonValue}
 * exactly as that stands to {@code ast.DataValue}.
 *
 * <p><b>A header is a property of the document, not of the root value</b> -- §2.2 says so outright: "Header
 * directives are properties of the document, not of the body's root value." That is why this is a wrapper
 * and not two more components on {@link TsonRecord} and its siblings. Every node type stays a pure value
 * that means the same thing wherever it appears, and the one place a directive could attach is the one
 * place §2.2 puts it.
 *
 * <p><b>Only a data document is representable here</b>, which is why there is no {@code meta} component: a
 * document carrying {@code !!meta} is a <em>schema</em> document, and its value model is
 * {@code schema.meta}, not this one. {@code TsonDocumentHeader} is the type that does carry all three, and
 * it exists for a different question -- classifying a document from its opening bytes before deciding how
 * to read it.
 *
 * <p><b>Both components are genuinely optional</b>, and neither absence is an error. A document may declare
 * an {@code !!id}, a {@code !!schema}, both, or neither; a bare value with no header at all is an ordinary
 * Class 1 document, and reading one yields a {@code TsonDocument} whose header components are empty rather
 * than one that pretends.
 *
 * @param id     the document's own identity ({@code !!id}), or empty
 * @param schema the schema governing its value ({@code !!schema}), or empty
 * @param root   the document's single value, itself carrying any annotations and type-ref it was written
 *               with -- never a directive
 */
public record TsonDocument(Optional<String> id, Optional<String> schema, TsonValue root) {

    public TsonDocument {
        id = id == null ? Optional.empty() : id;
        schema = schema == null ? Optional.empty() : schema;
    }

    /** A document with no header directives -- a bare value, which is every Class 1 document. */
    public static TsonDocument of(TsonValue root) {
        return new TsonDocument(Optional.empty(), Optional.empty(), root);
    }

    /** The same document under a different root, its header carried across unchanged. */
    public TsonDocument withRoot(TsonValue newRoot) {
        return new TsonDocument(id, schema, newRoot);
    }
}
