package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.TsonValueReader;
import io.ltr8.tson.parser.ast.CoreValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.TokenValue;

/**
 * Object-binding mode's own reading for meta-kernel's {@code boolean => !enum [true false]} -- the
 * one real enum instance whose members are meant to stand in for a genuine Java {@code Boolean}, not
 * raw member text. Every other enum instance is bound via the ordinary {@code EnumParser} (through
 * {@link AtomValueReader#ENUM_OBJECT_MODE}), reading the member token's own text as a {@code
 * String} -- exactly right for an arbitrary, user-defined enum label, and exactly wrong for {@code
 * boolean} specifically. DOM mode is untouched: {@link AtomValueReader#ENUM} keeps producing {@code
 * String} for {@code boolean} there too.
 */
final class BooleanReader implements TsonValueReader<Boolean> {

    static final BooleanReader INSTANCE = new BooleanReader();

    private BooleanReader() {
    }

    @Override
    public Boolean read(DataValue value) {
        if (value == null) {
            throw new IllegalArgumentException("expected a token for 'boolean', found no value");
        }
        CoreValue core = value.coreValue();
        if (!(core instanceof TokenValue token)) {
            throw new IllegalArgumentException("expected a token for 'boolean', found " + core);
        }
        return switch (token.text()) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException(
                    "expected 'true' or 'false' for 'boolean', found '" + token.text() + "'");
        };
    }
}
