package io.ltr8.tson.compiler.consumer;

import io.ltr8.tson.suite.data.Method;

/**
 * A meta-layer record holding a {@code method} value at a field of its own, so {@code ValuesByNameTest} can name a
 * value reached through a record value's field ({@code p.x.primary}).
 */
public record Pairing(Method primary) {
}
