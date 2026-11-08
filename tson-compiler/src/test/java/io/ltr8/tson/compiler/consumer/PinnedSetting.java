package io.ltr8.tson.compiler.consumer;

import io.ltr8.tson.schema.meta.Token;

/**
 * A vocabulary record whose dependent field's sibling is pinned, bound with no component for the sibling, so {@code
 * ValuesByNameTest} can name a value through the dependent field where the host holds no value for its sibling. A
 * schema document holds the dependent value as written, the token.
 */
public record PinnedSetting(Token value) {
}
