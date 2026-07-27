package io.ltr8.tson.parser.atom;

import io.ltr8.tson.parser.ast.TokenValue;

/**
 * Parses meta-kernel's {@code token} instance of the {@code unit} atom constructor (§4.2, §8.1) --
 * the lexical-token primitive: {@code !unit {}}'s canonical, NFC-normalised source lexeme, taken
 * verbatim. No constraints to hold ({@link io.ltr8.tson.schema.meta.Unit} itself declares no
 * fields), so unlike every other atom-family parser in this package, there's no {@code constraints}
 * field to wrap -- a singleton, not a record. {@link #read} accepts any token whatsoever,
 * unconstrained by definition, and returns its raw text.
 *
 * <p><b>{@code unit}'s own kernel doc is explicit that its instances -- {@code value}, {@code
 * token}, {@code void} -- are "distinguished by name and prose-level parsing contract, not by
 * schema shape"</b>: all three resolve to the identical empty body, so nothing in the resolved
 * schema itself can tell them apart. This class implements *only* {@code token}'s own contract now
 * (previously named {@code UnitParser} and shared, incorrectly, across all three -- see {@code
 * SPEC-FEEDBACK.md}). {@code value} routes through {@link io.ltr8.tson.parser.base.BaseTypeResolver}
 * instead ({@link ValueParser}), and {@code void} accepts only the absent sentinel {@code _}, not a
 * token at all -- see {@code io.ltr8.tson.parser.compiler.VoidParser}, since that
 * contract doesn't fit {@link AtomType}'s {@code read(TokenValue)} shape in the first place.
 *
 * <p>Not part of Part 1's published built-in vocabulary (§5) -- like {@link TextParser}/{@link
 * EnumParser}, never registered in {@link BuiltinTypeVocabulary} and has no {@code TYPENAME}
 * constant. {@code unit} is a Part 2 schema constructor, not a schemaless annotation a Class 1
 * processor would ever resolve on its own.
 */
public final class TokenParser implements AtomType<String> {

    public static final TokenParser INSTANCE = new TokenParser();

    private TokenParser() {
    }

    @Override
    public String read(TokenValue token) {
        return token.text();
    }

    @Override
    public String write(String value) {
        return value;
    }
}
