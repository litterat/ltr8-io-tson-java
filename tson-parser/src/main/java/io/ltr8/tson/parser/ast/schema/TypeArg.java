package io.ltr8.tson.parser.ast.schema;

import io.ltr8.tson.parser.ast.TokenValue;

/**
 * {@code type-arg = type-ref / value-literal} (Part 2 §12.1) -- one argument inside a generic
 * application's {@code <...>}.
 *
 * <p><b>An unquoted, non-numeric argument token always parses as {@link Ref}, never {@link
 * Value}.</b> §12.1's own prose is explicit that the two are not disambiguated by grammar for that
 * case: "whether it denotes a type or a value (an enum member, for instance) is settled against
 * the applied signature's parameter kinds (§5.10), not by the grammar." This parser resolves the
 * grammar-level part of that rule only -- a quoted token or one whose text matches [TSON-DATA]
 * §7.6's {@code number} production is unambiguously {@link Value}; everything else (including a
 * token that will later turn out to be an enum member, a value-parameter reference, or similar) is
 * parsed as {@link Ref} here, deferring the real classification to the semantic layer that has the
 * applied signature to consult. This mirrors {@code type-name}'s own numeric exclusion (§12.1's
 * terminals), so a genuinely numeric argument can never collide with a type-ref reading in the
 * first place.
 */
public sealed interface TypeArg {

    record Ref(TypeRef ref) implements TypeArg {
    }

    record Value(TokenValue value) implements TypeArg {
    }
}
