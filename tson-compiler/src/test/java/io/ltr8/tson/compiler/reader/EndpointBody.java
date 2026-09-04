package io.ltr8.tson.compiler.reader;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;

/**
 * A meta-layer constructor's body with no compiled reader -- [TSON-SCHEMA] §2.2.2's extension point, which
 * is the one route left to an entry the compiler can build no reader for now that every kernel and meta
 * constructor has one. A meta-schema declares {@code endpoint => data & { path: text }} and this is the
 * Java class it binds to; {@code ValueReaderFactoryRegistry} has no factory under that name and never could,
 * the constructor being a schema this library has never seen.
 *
 * <p>Test-only, and deliberately so: it stands for the gap in the tests that pin what a compiler does with
 * an entry it cannot build -- an {@code ErrorReader}, so the rest of the schema still compiles and only
 * reading a value against this entry reports {@code NOT_IMPLEMENTED}.
 */
@Typename(name = "endpoint")
record EndpointBody(String path) implements Data {
}
