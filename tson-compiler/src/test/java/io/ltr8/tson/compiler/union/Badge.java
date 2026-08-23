package io.ltr8.tson.compiler.union;

import io.ltr8.annotation.Typename;

/** An implementation of the open branch, carrying its own {@code @Typename}. */
@Typename(name = "badge")
public record Badge(String label) implements Decorated {
}
