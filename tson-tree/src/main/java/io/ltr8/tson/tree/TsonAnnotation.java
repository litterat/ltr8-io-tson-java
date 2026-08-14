package io.ltr8.tson.tree;

import java.util.Optional;

/**
 * An annotation on a {@link TsonValue} -- a name plus an optional value that is itself a {@link TsonValue}, so
 * the tree stays self-contained (unlike the grammar-layer {@code ast.Annotation}, whose value is a raw
 * {@code DataValue}).
 */
public record TsonAnnotation(String name, Optional<TsonValue> value) {
}
