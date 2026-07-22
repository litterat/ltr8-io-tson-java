package io.ltr8.tson.parser.ast;

/**
 * {@code core-value = record / map / array / empty-brace / absent / token} (§2.3, §7.4).
 *
 * <p>This is structural only: no base type resolution (§4) happens here, and none of the
 * built-in type vocabulary (§5) is interpreted — {@link TokenValue} preserves a token's exact
 * text and form, deferring interpretation to a later layer, per the spec's own layering
 * (§1.2 principle 1: "Value interpretation is deferred to base type resolution").
 */
public sealed interface CoreValue
        permits RecordValue, MapValue, ArrayValue, EmptyBrace, AbsentValue, TokenValue {
}
