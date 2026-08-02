package io.ltr8.tson.compiler.tree;

import java.util.Optional;

/**
 * An annotation on a {@link TsonNode} -- a name plus an optional value that is itself a {@link TsonNode}, so
 * the tree stays self-contained (unlike the grammar-layer {@code ast.Annotation}, whose value is a raw
 * {@code DataValue}).
 */
public record TsonAnnotation(String name, Optional<TsonNode> value) {
}
