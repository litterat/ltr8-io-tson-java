package io.ltr8.tson.compiler.union;

/** A sealed union with a non-sealed branch -- see {@code OpenBranchUnionWriteTest}. */
public sealed interface Shape permits Dot, Decorated {
}
