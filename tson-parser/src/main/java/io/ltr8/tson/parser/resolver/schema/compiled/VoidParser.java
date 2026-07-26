package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.ast.AbsentValue;
import io.ltr8.tson.parser.ast.DataValue;

/**
 * Parses meta-kernel's {@code void} instance of the {@code unit} atom constructor (§4.2, §8.1) --
 * per its own kernel doc, "parsing contract admits only the absent sentinel {@code _}. The host
 * value is absent." Unlike {@code token}/{@code value} (see {@code
 * io.ltr8.tson.parser.resolver.vocab.TokenParser}/{@code ValueParser}), that contract can't be
 * expressed as an {@code io.ltr8.tson.parser.resolver.vocab.AtomType<T>} at all -- {@code
 * AtomType.read(TokenValue)} only ever sees a token, and {@code _} isn't one ({@link AbsentValue}
 * is a distinct {@code CoreValue} variant, a sibling of {@code TokenValue}, not a kind of it) -- so
 * this lives here, as an ordinary {@link TsonSchemaTypeParser} reading the {@link DataValue} directly,
 * rather than being adapted through {@link AtomTypeParser} the way every other atom-family parser
 * is.
 *
 * <p>Reads to plain Java {@code null} -- "the host value is absent" has no more specific natural
 * representation. Anything other than {@link AbsentValue} (any real token, record, array, ...) is
 * rejected outright, matching the contract exactly: {@code void} is not "any value, including
 * absent," it is "only absent."
 */
final class VoidParser implements TsonSchemaTypeParser<Object> {

    static final VoidParser INSTANCE = new VoidParser();

    private VoidParser() {
    }

    @Override
    public Object read(DataValue value) {
        if (value == null || !(value.coreValue() instanceof AbsentValue)) {
            throw new IllegalArgumentException(
                    "expected the absent sentinel '_' for void, found " + (value == null ? "no value" : value.coreValue()));
        }
        return null;
    }
}
