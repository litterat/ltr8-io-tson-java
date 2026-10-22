package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;

/** A record family base: a value at a {@code gap_endpoint}-typed slot is tagged with the subtype it is. */
@Typename(name = "gap_endpoint")
public sealed interface GapEndpoint permits GapOp {

    int status();
}
