package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.ast.CoreValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.resolver.vocab.AtomType;
import io.ltr8.tson.parser.resolver.vocab.BinaryParser;
import io.ltr8.tson.parser.resolver.vocab.DateParser;
import io.ltr8.tson.parser.resolver.vocab.DateTimeParser;
import io.ltr8.tson.parser.resolver.vocab.DecimalParser;
import io.ltr8.tson.parser.resolver.vocab.DurationParser;
import io.ltr8.tson.parser.resolver.vocab.EnumParser;
import io.ltr8.tson.parser.resolver.vocab.FloatParser;
import io.ltr8.tson.parser.resolver.vocab.IntegerParser;
import io.ltr8.tson.parser.resolver.vocab.RationalParser;
import io.ltr8.tson.parser.resolver.vocab.RegexParser;
import io.ltr8.tson.parser.resolver.vocab.TextParser;
import io.ltr8.tson.parser.resolver.vocab.TimeParser;
import io.ltr8.tson.parser.resolver.vocab.TokenParser;
import io.ltr8.tson.parser.resolver.vocab.UriParser;
import io.ltr8.tson.parser.resolver.vocab.UuidParser;
import io.ltr8.tson.parser.resolver.vocab.ValueParser;
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
 * Adapts any {@code resolver.vocab} {@link AtomType} into a {@link TsonTypeParser} -- the one place
 * a compiled position backed by an atom-family constructor (§5.5's {@code integer_type}, {@code
 * text_type}, ...) bridges from {@link DataValue} (what every {@link TsonTypeParser} reads) down to
 * {@link TokenValue} (what {@link AtomType} itself reads).
 *
 * <p>Deliberately kept in this package, not {@code resolver.vocab} itself -- {@code resolver.vocab}
 * stays unaware the compiled-schema-parser layer exists at all, the same one-way direction it
 * already keeps toward {@code resolver.schema}: vocab is consumed by higher layers, it doesn't
 * import from them.
 *
 * <p><b>Every atom-family {@link TsonParserFactory} lives here too, as a {@code static final}
 * constant</b>, one per constructor name -- not as a separate one-class-per-constructor file the
 * way an earlier version of this package did it. Each is a one-line cast-and-adapt, identical in
 * shape (see {@link #INTEGER_TYPE} for the pattern), so a whole file per constructor was pure
 * boilerplate; a caller assembling a {@link ParserFactoryRegistry} just does {@code
 * .register("integer_type", AtomTypeParser.INTEGER_TYPE)}. {@link #URI_TYPE}/{@link #REGEX_TYPE}
 * were initially left out of this package, mistakenly grouped with a real, separate gap
 * ({@code UriType}/{@code RegexType}'s own *schema-resolution*-time defaulting, see {@code
 * MetaKernelParser}'s own Javadoc) that has nothing to do with reading data against an
 * already-correctly-resolved schema -- by the time a real {@code uri}/{@code regex} entry reaches
 * this layer, {@code MetaKernelParser}'s own hand-picked binding has already filled in {@code
 * specification}/{@code constraints} correctly, so these two work exactly like every other family;
 * confirmed against the real resolved entries, not just reasoned about.
 */
final class AtomTypeParser<T> implements TsonTypeParser<T> {

    static final TsonParserFactory INTEGER_TYPE = (name, definition, ctx) ->
            new AtomTypeParser<>(new IntegerParser((IntegerType) definition.body()));
    static final TsonParserFactory TEXT_TYPE = (name, definition, ctx) ->
            new AtomTypeParser<>(new TextParser((TextType) definition.body()));
    static final TsonParserFactory DECIMAL_TYPE = (name, definition, ctx) ->
            new AtomTypeParser<>(new DecimalParser((DecimalType) definition.body()));
    static final TsonParserFactory FLOAT_TYPE = (name, definition, ctx) ->
            new AtomTypeParser<>(new FloatParser((FloatType) definition.body()));
    static final TsonParserFactory RATIONAL_TYPE = (name, definition, ctx) ->
            new AtomTypeParser<>(new RationalParser((RationalType) definition.body()));
    static final TsonParserFactory UUID_TYPE = (name, definition, ctx) ->
            new AtomTypeParser<>(new UuidParser((UuidType) definition.body()));
    /** Registered under {@code "binary"}, not {@code "binary_type"} -- {@link BinaryType}'s own {@code @Typename} matches the real spec constructor name; see its own Javadoc. */
    static final TsonParserFactory BINARY = (name, definition, ctx) ->
            new AtomTypeParser<>(new BinaryParser((BinaryType) definition.body()));
    static final TsonParserFactory DATE_TYPE = (name, definition, ctx) ->
            new AtomTypeParser<>(new DateParser((DateType) definition.body()));
    static final TsonParserFactory TIME_TYPE = (name, definition, ctx) ->
            new AtomTypeParser<>(new TimeParser((TimeType) definition.body()));
    static final TsonParserFactory DATETIME_TYPE = (name, definition, ctx) ->
            new AtomTypeParser<>(new DateTimeParser((DateTimeType) definition.body()));
    static final TsonParserFactory DURATION_TYPE = (name, definition, ctx) ->
            new AtomTypeParser<>(new DurationParser((DurationType) definition.body()));
    static final TsonParserFactory URI_TYPE = (name, definition, ctx) ->
            new AtomTypeParser<>(new UriParser((UriType) definition.body()));
    static final TsonParserFactory REGEX_TYPE = (name, definition, ctx) ->
            new AtomTypeParser<>(new RegexParser((RegexType) definition.body()));
    static final TsonParserFactory ENUM = (name, definition, ctx) ->
            new AtomTypeParser<>(new EnumParser((EnumBody) definition.body()));
    /**
     * {@code unit}'s three real instances -- {@code value}/{@code token}/{@code void} -- all
     * resolve to the identical empty body (no constraint fields to distinguish them by), so, per
     * the kernel's own doc ("distinguished by name and prose-level parsing contract, not by schema
     * shape"), dispatch here is keyed on the *declaration's own name* (this factory's {@code name}
     * parameter), not on {@code definition.body()} the way every other constant in this class is.
     * {@code void} doesn't even fit {@link AtomType}'s {@code read(TokenValue)} shape (its contract
     * is "accept only the absent sentinel `_`", which isn't a token at all) so it bypasses {@link
     * AtomTypeParser} entirely, unlike {@code value}/{@code token}. An unrecognized {@code unit}-
     * constructed name (a schema author's own new instance of the constructor, with its own prose
     * contract this codebase doesn't know) falls back to {@link TokenParser}'s raw-text behavior --
     * the same default this whole family had before the split -- rather than failing outright.
     */
    static final TsonParserFactory UNIT = (name, definition, ctx) -> switch (name) {
        case "void" -> VoidParser.INSTANCE;
        case "value" -> new AtomTypeParser<>(ValueParser.INSTANCE);
        default -> new AtomTypeParser<>(TokenParser.INSTANCE);
    };

    private final AtomType<T> delegate;

    AtomTypeParser(AtomType<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public T read(DataValue value) {
        if (value == null) {
            // A genuinely missing (not merely absent-sentinel) value -- RecordParser already
            // guards this before ever calling a child's read(), but a caller reaching this
            // directly (or a future composite parser that doesn't guard) gets a clear error
            // instead of an NPE from value.coreValue() below.
            throw new IllegalArgumentException("expected a token for " + delegate + ", found no value");
        }
        CoreValue core = value.coreValue();
        if (!(core instanceof TokenValue token)) {
            throw new IllegalArgumentException("expected a token for " + delegate + ", found " + core);
        }
        return delegate.read(token);
    }
}
