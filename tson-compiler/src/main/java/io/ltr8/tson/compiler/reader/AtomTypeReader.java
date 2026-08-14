package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.AtomType;
import io.ltr8.tson.compiler.atom.AtomTypeException;
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
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
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
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.TimeType;
import io.ltr8.tson.schema.meta.UriType;
import io.ltr8.tson.schema.meta.UuidType;

import java.util.Optional;

/**
 * Adapts an {@code atom} {@link AtomType} into a {@link TsonTypeReader} -- this package's own copy
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
final class AtomTypeReader<T> implements TsonTypeReader<T> {

    static final ValueReaderFactory INTEGER_TYPE = (_, definition, _) ->
            new AtomTypeReader<>(new IntegerParser((IntegerType) definition.body()), definition.position());
    static final ValueReaderFactory TEXT_TYPE = (_, definition, _) ->
            new AtomTypeReader<>(new TextParser((TextType) definition.body()), definition.position());
    static final ValueReaderFactory DECIMAL_TYPE = (_, definition, _) ->
            new AtomTypeReader<>(new DecimalParser((DecimalType) definition.body()), definition.position());
    static final ValueReaderFactory FLOAT_TYPE = (_, definition, _) ->
            new AtomTypeReader<>(new FloatParser((FloatType) definition.body()), definition.position());
    static final ValueReaderFactory RATIONAL_TYPE = (_, definition, _) ->
            new AtomTypeReader<>(new RationalParser((RationalType) definition.body()), definition.position());
    static final ValueReaderFactory UUID_TYPE = (_, definition, _) ->
            new AtomTypeReader<>(new UuidParser((UuidType) definition.body()), definition.position());
    /** Registered under {@code "binary"}, not {@code "binary_type"} -- {@link BinaryType}'s own {@code @Typename} matches the real spec constructor name. */
    static final ValueReaderFactory BINARY = (_, definition, _) ->
            new AtomTypeReader<>(new BinaryParser((BinaryType) definition.body()), definition.position());
    static final ValueReaderFactory DATE_TYPE = (_, definition, _) ->
            new AtomTypeReader<>(new DateParser((DateType) definition.body()), definition.position());
    static final ValueReaderFactory TIME_TYPE = (_, definition, _) ->
            new AtomTypeReader<>(new TimeParser((TimeType) definition.body()), definition.position());
    static final ValueReaderFactory DATETIME_TYPE = (_, definition, _) ->
            new AtomTypeReader<>(new DateTimeParser((DateTimeType) definition.body()), definition.position());
    static final ValueReaderFactory DURATION_TYPE = (_, definition, _) ->
            new AtomTypeReader<>(new DurationParser((DurationType) definition.body()), definition.position());
    static final ValueReaderFactory URI_TYPE = (_, definition, _) ->
            new AtomTypeReader<>(new UriParser((UriType) definition.body()), definition.position());
    static final ValueReaderFactory REGEX_TYPE = (_, definition, _) ->
            new AtomTypeReader<>(new RegexParser((RegexType) definition.body()), definition.position());
    /** {@code complex_type} has nothing to configure ({@code component} is fixed, not modeled -- see {@link ComplexParser}'s own Javadoc), so this ignores {@code definition}'s own body entirely (though not its position) and always wraps the one {@link ComplexParser#UNCONSTRAINED} singleton. */
    static final ValueReaderFactory COMPLEX_TYPE = (_, definition, _) ->
            new AtomTypeReader<>(ComplexParser.UNCONSTRAINED, definition.position());
    /** {@code within}/{@code excluding} ({@code schema.meta.Ipv4Type}'s own fields) aren't modeled by {@link Ipv4Parser} -- see its own Javadoc -- so, like {@link #COMPLEX_TYPE}, this ignores {@code definition}'s own body. */
    static final ValueReaderFactory IPV4_TYPE = (_, definition, _) ->
            new AtomTypeReader<>(Ipv4Parser.UNCONSTRAINED, definition.position());
    /** Same reasoning as {@link #IPV4_TYPE}, for {@link Ipv6Parser}. */
    static final ValueReaderFactory IPV6_TYPE = (_, definition, _) ->
            new AtomTypeReader<>(Ipv6Parser.UNCONSTRAINED, definition.position());
    /**
     * The enum reader for both tree and object-binding modes: {@code boolean} reads a real {@code Boolean}
     * ({@link BooleanReader}), every other enum instance its member text ({@link EnumParser}). Dispatch is
     * keyed on the declaration's own name, the same mechanism {@link #UNIT} uses for {@code value}/{@code
     * token}/{@code void}. (Tree mode then wraps the result in an {@code AtomNode} -- see {@link
     * ValueReaderFactoryRegistry}.)
     */
    static final ValueReaderFactory ENUM_OBJECT_MODE = (name, definition, _) ->
            "boolean".equals(name)
                    ? new BooleanReader(definition.position())
                    : new AtomTypeReader<>(new EnumParser((EnumBody) definition.body()), definition.position());
    /**
     * {@code unit}'s three real instances -- {@code value}/{@code token}/{@code void} -- all
     * resolve to the identical empty body, so, per the kernel's own doc ("distinguished by name and
     * prose-level parsing contract, not by schema shape"), dispatch here is keyed on the
     * declaration's own name, not its resolved shape. {@code void} doesn't fit {@link AtomType}'s
     * {@code read(TokenValue)} shape at all (its contract admits only the absent sentinel {@code _},
     * not a token), so it bypasses this class entirely via {@link VoidReader}. An unrecognized
     * {@code unit}-constructed name falls back to {@link TokenParser}'s raw-text behavior.
     */
    static final ValueReaderFactory UNIT = (name, definition, _) -> switch (name) {
        case "void" -> new VoidReader(definition.position());
        case "value" -> new AtomTypeReader<>(ValueParser.INSTANCE, definition.position());
        default -> new AtomTypeReader<>(TokenParser.INSTANCE, definition.position());
    };

    private final AtomType<T> delegate;
    private final Optional<SourcePosition> schemaPosition;

    private AtomTypeReader(AtomType<T> delegate, Optional<SourcePosition> schemaPosition) {
        this.delegate = delegate;
        this.schemaPosition = schemaPosition;
    }

    @Override
    public T read(TsonReadContext ctx) {
        ctx = ctx.withSchemaPosition(schemaPosition);
        EventSkip.annotationsAndTypeRef(ctx);
        TsonEvent e = ctx.peek();
        if (!(e instanceof TokenEvent token)) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected a token for " + delegate + ", found " + e,
                    "a token", String.valueOf(e));
            EventSkip.coreValue(ctx);
            return null;
        }
        ctx.next();
        TokenValue tokenValue = new TokenValue(token.text(), token.form());
        try {
            return delegate.read(tokenValue);
        } catch (AtomTypeException ex) {
            ctx.report(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, ex.getMessage(), "a value satisfying " + delegate,
                    token.text());
            return null;
        }
    }
}
