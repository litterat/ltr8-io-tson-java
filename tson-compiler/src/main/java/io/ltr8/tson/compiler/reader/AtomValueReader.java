package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.AtomType;
import io.ltr8.tson.compiler.atom.BinaryParser;
import io.ltr8.tson.compiler.atom.ComplexParser;
import io.ltr8.tson.compiler.atom.DateParser;
import io.ltr8.tson.compiler.atom.DateTimeParser;
import io.ltr8.tson.compiler.atom.DecimalParser;
import io.ltr8.tson.compiler.atom.DurationParser;
import io.ltr8.tson.compiler.atom.EnumParser;
import io.ltr8.tson.compiler.atom.FloatParser;
import io.ltr8.tson.compiler.atom.IntegerParser;
import io.ltr8.tson.compiler.atom.Ipv4Parser;
import io.ltr8.tson.compiler.atom.Ipv6Parser;
import io.ltr8.tson.compiler.atom.RationalParser;
import io.ltr8.tson.compiler.atom.RegexParser;
import io.ltr8.tson.compiler.atom.TextParser;
import io.ltr8.tson.compiler.atom.TimeParser;
import io.ltr8.tson.compiler.atom.TokenParser;
import io.ltr8.tson.compiler.atom.UriParser;
import io.ltr8.tson.compiler.atom.UuidParser;
import io.ltr8.tson.compiler.atom.ValueParser;
import io.ltr8.tson.schema.meta.BinaryType;
import io.ltr8.tson.schema.meta.DateTimeType;
import io.ltr8.tson.schema.meta.DateType;
import io.ltr8.tson.schema.meta.DecimalType;
import io.ltr8.tson.schema.meta.DurationType;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.FloatType;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RationalType;
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.TimeType;
import io.ltr8.tson.schema.meta.UriType;
import io.ltr8.tson.schema.meta.UuidType;

/**
 * Adapts an {@code atom} {@link AtomType} into a {@link TsonValueReader} -- this package's own copy
 * of {@code reader.AtomTypeParser}, not a reuse of it. Deliberately duplicated rather than shared:
 * {@code reader.TsonParserFactoryRegistry} is going away and some of these classes may move again
 * before this package settles, so everything it needs stays self-contained here in the meantime
 * rather than reaching back into {@code reader}.
 *
 * <p>Every atom-family {@link ValueReaderFactory} lives here too, as a {@code static final}
 * constant, one per constructor name -- see {@link ValueReaderFactoryRegistry} for where they
 * actually get registered. {@code resolver} is unused by every one of these (an atom never needs to
 * resolve a child), and {@code name} only by {@link #ENUM_OBJECT_MODE}/{@link #UNIT}, both keyed on
 * the declaration's own name rather than its resolved shape -- see each one's own note.
 */
final class AtomValueReader<T> implements TsonValueReader<T> {

    static final ValueReaderFactory INTEGER_TYPE = (_, definition, _) ->
            new AtomValueReader<>(new IntegerParser((IntegerType) definition.body()));
    static final ValueReaderFactory TEXT_TYPE = (_, definition, _) ->
            new AtomValueReader<>(new TextParser((TextType) definition.body()));
    static final ValueReaderFactory DECIMAL_TYPE = (_, definition, _) ->
            new AtomValueReader<>(new DecimalParser((DecimalType) definition.body()));
    static final ValueReaderFactory FLOAT_TYPE = (_, definition, _) ->
            new AtomValueReader<>(new FloatParser((FloatType) definition.body()));
    static final ValueReaderFactory RATIONAL_TYPE = (_, definition, _) ->
            new AtomValueReader<>(new RationalParser((RationalType) definition.body()));
    static final ValueReaderFactory UUID_TYPE = (_, definition, _) ->
            new AtomValueReader<>(new UuidParser((UuidType) definition.body()));
    /** Registered under {@code "binary"}, not {@code "binary_type"} -- {@link BinaryType}'s own {@code @Typename} matches the real spec constructor name. */
    static final ValueReaderFactory BINARY = (_, definition, _) ->
            new AtomValueReader<>(new BinaryParser((BinaryType) definition.body()));
    static final ValueReaderFactory DATE_TYPE = (_, definition, _) ->
            new AtomValueReader<>(new DateParser((DateType) definition.body()));
    static final ValueReaderFactory TIME_TYPE = (_, definition, _) ->
            new AtomValueReader<>(new TimeParser((TimeType) definition.body()));
    static final ValueReaderFactory DATETIME_TYPE = (_, definition, _) ->
            new AtomValueReader<>(new DateTimeParser((DateTimeType) definition.body()));
    static final ValueReaderFactory DURATION_TYPE = (_, definition, _) ->
            new AtomValueReader<>(new DurationParser((DurationType) definition.body()));
    static final ValueReaderFactory URI_TYPE = (_, definition, _) ->
            new AtomValueReader<>(new UriParser((UriType) definition.body()));
    static final ValueReaderFactory REGEX_TYPE = (_, definition, _) ->
            new AtomValueReader<>(new RegexParser((RegexType) definition.body()));
    /** {@code complex_type} has nothing to configure ({@code component} is fixed, not modeled -- see {@link ComplexParser}'s own Javadoc), so this ignores {@code definition} entirely and always wraps the one {@link ComplexParser#UNCONSTRAINED} singleton. */
    static final ValueReaderFactory COMPLEX_TYPE = (_, _, _) -> new AtomValueReader<>(ComplexParser.UNCONSTRAINED);
    /** {@code within}/{@code excluding} ({@code schema.meta.Ipv4Type}'s own fields) aren't modeled by {@link Ipv4Parser} -- see its own Javadoc -- so, like {@link #COMPLEX_TYPE}, this ignores {@code definition} entirely. */
    static final ValueReaderFactory IPV4_TYPE = (_, _, _) -> new AtomValueReader<>(Ipv4Parser.UNCONSTRAINED);
    /** Same reasoning as {@link #IPV4_TYPE}, for {@link Ipv6Parser}. */
    static final ValueReaderFactory IPV6_TYPE = (_, _, _) -> new AtomValueReader<>(Ipv6Parser.UNCONSTRAINED);
    static final ValueReaderFactory ENUM = (_, definition, _) ->
            new AtomValueReader<>(new EnumParser((EnumBody) definition.body()));
    /**
     * Object-binding mode's own variant of {@link #ENUM} -- identical for every enum instance
     * except {@code boolean} itself, which reads real {@code Boolean} values ({@link BooleanReader})
     * instead of raw member text. Dispatch is keyed on the declaration's own name, the same
     * mechanism {@link #UNIT} uses for {@code value}/{@code token}/{@code void} -- every other enum
     * instance falls through to ordinary {@link #ENUM} behavior. DOM mode never registers this;
     * {@link ValueReaderFactoryRegistry#dom()} uses {@link #ENUM} for {@code boolean} too, since DOM
     * has no target Java type to reconcile {@code "true"}/{@code "false"} against.
     */
    static final ValueReaderFactory ENUM_OBJECT_MODE = (name, definition, _) ->
            "boolean".equals(name)
                    ? BooleanReader.INSTANCE
                    : new AtomValueReader<>(new EnumParser((EnumBody) definition.body()));
    /**
     * {@code unit}'s three real instances -- {@code value}/{@code token}/{@code void} -- all
     * resolve to the identical empty body, so, per the kernel's own doc ("distinguished by name and
     * prose-level parsing contract, not by schema shape"), dispatch here is keyed on the
     * declaration's own name, not its resolved shape. {@code void} doesn't fit {@link AtomType}'s
     * {@code read(TokenValue)} shape at all (its contract admits only the absent sentinel {@code _},
     * not a token), so it bypasses this class entirely via {@link VoidReader}. An unrecognized
     * {@code unit}-constructed name falls back to {@link TokenParser}'s raw-text behavior.
     */
    static final ValueReaderFactory UNIT = (name, _, _) -> switch (name) {
        case "void" -> VoidReader.INSTANCE;
        case "value" -> new AtomValueReader<>(ValueParser.INSTANCE);
        default -> new AtomValueReader<>(TokenParser.INSTANCE);
    };

    private final AtomType<T> delegate;

    private AtomValueReader(AtomType<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public T read(DataValue value) {
        if (value == null) {
            throw new IllegalArgumentException("expected a token for " + delegate + ", found no value");
        }
        CoreValue core = value.coreValue();
        if (!(core instanceof TokenValue token)) {
            throw new IllegalArgumentException("expected a token for " + delegate + ", found " + core);
        }
        return delegate.read(token);
    }
}
