package io.ltr8.tson.parser.ast;

/**
 * {@code empty-brace = "{" ws "}"} (§2.8).
 *
 * <p>Deliberately its own {@link CoreValue} case, not resolved to an empty {@link RecordValue}
 * or {@link MapValue} here: the spec defers that choice to the resolver ("In the absence of
 * declared type information, an empty-brace resolves to an empty record. When a higher part
 * supplies an expected type, it resolves to the empty container of that type", §2.8) — a layer
 * this parser doesn't have yet.
 */
public record EmptyBrace() implements CoreValue {
}
