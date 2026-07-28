package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.TsonValueReader;
import io.ltr8.tson.parser.ast.AbsentValue;
import io.ltr8.tson.parser.ast.DataValue;

/**
 * Parses meta-kernel's {@code void} instance of the {@code unit} atom constructor -- per its own
 * kernel doc, "parsing contract admits only the absent sentinel {@code _}. The host value is
 * absent." That contract can't be expressed as an {@code io.ltr8.tson.parser.atom.AtomType<T>} at
 * all ({@code AtomType.read(TokenValue)} only ever sees a token, and {@code _} isn't one), so this
 * reads the {@link DataValue} directly rather than going through {@link AtomValueReader}.
 *
 * <p>Reads to plain Java {@code null} -- "the host value is absent" has no more specific natural
 * representation. Anything other than {@link AbsentValue} is rejected outright.
 */
final class VoidReader implements TsonValueReader<Object> {

    static final VoidReader INSTANCE = new VoidReader();

    private VoidReader() {
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
