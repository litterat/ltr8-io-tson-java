package io.ltr8.tson.compiler.union;

import io.ltr8.annotation.Typename;

/** A record whose component is union-typed, so writing it goes through {@code writeUnion}. */
@Typename(name = "holder")
public record Holder(Shape shape) {
}
