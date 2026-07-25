package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;

/**
 * Parses meta-kernel's {@code unit} atom constructor (§4.2, §8.1): an empty marker, {@code !unit
 * {}} -- the body of {@code value}, {@code token}, and {@code void}. No constraints to hold ({@link
 * io.ltr8.tson.schema.meta.Unit} itself declares no fields), so unlike every other atom-family
 * parser in this package, there's no {@code constraints} field to wrap -- a singleton, not a
 * record. {@link #read} accepts any token whatsoever, unconstrained by definition, and returns its
 * raw text.
 *
 * <p>Not part of Part 1's published built-in vocabulary (§5) -- like {@link TextParser}/{@link
 * EnumParser}, never registered in {@link BuiltinTypeVocabulary} and has no {@code TYPENAME}
 * constant. {@code unit} is a Part 2 schema constructor, not a schemaless annotation a Class 1
 * processor would ever resolve on its own.
 */
public final class UnitParser implements AtomType<String> {

    public static final UnitParser INSTANCE = new UnitParser();

    private UnitParser() {
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
