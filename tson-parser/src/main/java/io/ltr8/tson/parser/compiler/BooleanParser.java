package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.TsonValueReader;
import io.ltr8.tson.parser.ast.CoreValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.atom.EnumParser;

/**
 * Object-binding mode's own reading for meta-kernel's {@code boolean => !enum [true false]} --
 * the one real enum instance whose members are meant to stand in for a genuine Java {@code
 * Boolean}, not raw member text. Every other enum instance (`product_access_type`, `field_state`,
 * `binary_encoding`, ...) is bound via the ordinary {@link
 * EnumParser} (through {@link AtomTypeParser#ENUM}), reading
 * the member token's own text as a {@code String} -- exactly right for an arbitrary, user-defined
 * enum label, and exactly wrong for {@code boolean} specifically, whose two members are meant to
 * *be* the two Java boolean values, not the strings {@code "true"}/{@code "false"}.
 *
 * <p>Dispatch to this class is name-keyed, not shape-keyed -- see {@link
 * AtomTypeParser#ENUM_OBJECT_MODE}'s own Javadoc for why (the same mechanism {@link
 * AtomTypeParser#UNIT} already uses for {@code value}/{@code token}/{@code void}, all three
 * resolving to the identical empty body). DOM mode is deliberately untouched: {@link
 * AtomTypeParser#ENUM} keeps producing {@code String} for {@code boolean} there too, matching
 * already-established, already-tested behavior (e.g. {@code MetaKernelEndToEndTest}'s own
 * {@code "true"}, not a Java boolean" assertion) -- this class only ever gets registered under
 * {@code "enum"} in {@link TsonParserFactoryRegistry#object}.
 */
final class BooleanParser implements TsonValueReader<Boolean> {

    static final BooleanParser INSTANCE = new BooleanParser();

    private BooleanParser() {
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
