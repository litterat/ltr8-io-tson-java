package io.ltr8.tson.tree;

import java.util.List;
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
 * <p><b>Only a data document is representable here</b>: a document whose root is tagged {@code !schema} is a
 * <em>schema</em> document, and its value model is {@code schema.meta}, not this one.
 *
 * <p><b>Neither component is required</b>, and neither absence is an error. A document may declare an {@code !!id},
 * any number of {@code !!import}s, both, or neither; a bare value with no header at all is an ordinary Class 1
 * document, and reading one yields a {@code TsonDocument} whose header components are empty rather than one that
 * pretends.
 *
 * @param id      the document's own identity ({@code !!id}), or empty
 * @param imports the schemas its value is read under ({@code !!import}), in header order
 * @param root    the document's single value, itself carrying any annotations and type-ref it was written
 *                with -- never a directive
 */
public record TsonDocument(Optional<String> id, List<String> imports, TsonValue root) {

    public TsonDocument {
        id = id == null ? Optional.empty() : id;
        imports = imports == null ? List.of() : List.copyOf(imports);
    }

    /** A document with no header directives -- a bare value, which is every Class 1 document. */
    public static TsonDocument of(TsonValue root) {
        return new TsonDocument(Optional.empty(), List.of(), root);
    }

    /** The same document under a different root, its header carried across unchanged. */
    public TsonDocument withRoot(TsonValue newRoot) {
        return new TsonDocument(id, imports, newRoot);
    }
}
