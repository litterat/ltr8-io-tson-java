package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;

/** A record family base with no field of its own. */
@Typename(name = "sb_endpoint")
public sealed interface SbEndpoint permits SbOp {
}
