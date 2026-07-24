package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

/**
 * The meta-kernel's {@code unit} atom constructor's own vocabulary, resolved (Part 2 §4.2, §8.1):
 * an empty marker, {@code !unit {}} -- the body of {@code value}, {@code token}, and {@code void}
 * (and core's own {@code void} sibling), the "atom with no constraint vocabulary" (§4.2).
 */
@Typename(name = "unit")
public record Unit() implements Atom {
}
