package io.ltr8.tson.compiler.ast;

import java.util.List;
import java.util.Optional;

/**
 * A data document (§2.2, §7.4): an optional {@code !!id}, any number of {@code !!import}s, and one data-value.
 *
 * <p>Only data documents are representable here. A document whose root is tagged {@code !schema} is a
 * <em>schema</em> document, which a Class 1 processor rejects with a categorized diagnostic (§1.5, §8.1) rather than
 * producing a {@code Document}; see {@code io.ltr8.tson.compiler.TsonUnsupportedDocumentException}.
 *
 * <p>{@code id} and {@code imports} are the header directives' raw URI arguments, uninterpreted, the imports in
 * header order. {@code root} is the document's single value — itself an ordinary data-value that may carry its own
 * annotations and type reference, but never a directive (§2.2: "Header directives are properties of the document,
 * not of the body's root value").
 */
public record Document(Optional<String> id, List<String> imports, DataValue root) {

    public Document {
        imports = List.copyOf(imports);
    }
}
