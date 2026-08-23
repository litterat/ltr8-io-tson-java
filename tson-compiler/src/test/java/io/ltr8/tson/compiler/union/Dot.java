package io.ltr8.tson.compiler.union;

import io.ltr8.annotation.Typename;

/** The closed leaf. */
@Typename(name = "dot")
public record Dot(int x, int y) implements Shape {
}
