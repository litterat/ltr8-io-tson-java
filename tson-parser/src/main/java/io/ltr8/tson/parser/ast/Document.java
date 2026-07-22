package io.ltr8.tson.parser.ast;

import java.util.Optional;

/**
 * A data document (§2.2, §7.4): {@code document = [id-directive] ws data-doc},
 * {@code data-doc = [schema-directive ws] data-value ws}.
 *
 * <p>Only data documents are representable here. A document whose header contains
 * {@code !!meta} is a <em>schema</em> document, which this parser does not support — a Class 1
 * processor rejects it with a categorized diagnostic (§1.5, §8.1) rather than producing a
 * {@code Document}; see {@code io.ltr8.tson.parser.SchemaDocumentException}.
 *
 * <p>{@code id} and {@code schema} are the header directives' raw URI arguments, uninterpreted.
 * {@code root} is the document's single value — itself an ordinary data-value that may carry its
 * own annotations and type reference, but never a directive (§2.2: "Header directives are
 * properties of the document, not of the body's root value").
 */
public record Document(Optional<String> id, Optional<String> schema, DataValue root) {
}
