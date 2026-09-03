package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.AtomType;
import io.ltr8.tson.compiler.atom.AtomTypeException;
import io.ltr8.tson.compiler.atom.BytesParser;
import io.ltr8.tson.compiler.atom.Cidr4Parser;
import io.ltr8.tson.compiler.atom.Cidr6Parser;
import io.ltr8.tson.compiler.atom.ComplexParser;
import io.ltr8.tson.compiler.atom.DateParser;
import io.ltr8.tson.compiler.atom.DateTimeParser;
import io.ltr8.tson.compiler.atom.DecimalParser;
import io.ltr8.tson.compiler.atom.DurationParser;
import io.ltr8.tson.compiler.atom.PeriodParser;
import io.ltr8.tson.compiler.atom.EmailParser;
import io.ltr8.tson.compiler.atom.EnumParser;
import io.ltr8.tson.compiler.atom.FloatParser;
import io.ltr8.tson.compiler.atom.IntegerParser;
import io.ltr8.tson.compiler.atom.Ipv4Parser;
import io.ltr8.tson.compiler.atom.Ipv6Parser;
import io.ltr8.tson.compiler.atom.MacParser;
import io.ltr8.tson.compiler.atom.RationalParser;
import io.ltr8.tson.compiler.atom.RegexParser;
import io.ltr8.tson.compiler.atom.TextParser;
import io.ltr8.tson.compiler.atom.TimeParser;
import io.ltr8.tson.compiler.atom.IdentifierParser;
import io.ltr8.tson.compiler.atom.UriParser;
import io.ltr8.tson.compiler.atom.UuidParser;
import io.ltr8.tson.compiler.atom.ValueParser;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.schema.meta.BytesType;
import io.ltr8.tson.schema.meta.Cidr4Type;
import io.ltr8.tson.schema.meta.Cidr6Type;
import io.ltr8.tson.schema.meta.DateTimeType;
import io.ltr8.tson.schema.meta.DateType;
import io.ltr8.tson.schema.meta.DecimalType;
import io.ltr8.tson.schema.meta.EmailType;
import io.ltr8.tson.schema.meta.DurationType;
import io.ltr8.tson.schema.meta.PeriodType;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.FloatType;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.MacType;
import io.ltr8.tson.schema.meta.RationalType;
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.TimeType;
import io.ltr8.tson.schema.meta.UriType;
import io.ltr8.tson.schema.meta.UuidType;


/**
 * Adapts an {@code atom} {@link AtomType} into a {@link TsonTypeReader} -- this package's own copy
 * of {@code reader.AtomTypeParser}, not a reuse of it. Deliberately duplicated rather than shared:
 * {@code reader.TsonParserFactoryRegistry} is going away and some of these classes may move again
 * before this package settles, so everything it needs stays self-contained here in the meantime
 * rather than reaching back into {@code reader}.
 *
 * <p>Every atom-family {@link ValueReaderFactory} lives here too, as a {@code static final}
 * constant, one per constructor name -- see {@link ValueReaderFactoryRegistry} for where they
 * actually get registered. Every one of these reaches {@code context} only for
 * {@link ValueReaderContext#locationOf} (an atom never needs to resolve a child), and {@code name}
 * additionally in {@link #ENUM_OBJECT_MODE}/{@link #UNIT}, both keyed on the declaration's own name rather
 * than its resolved shape -- see each one's own note.
 */
final class AtomTypeReader<T> implements TsonTypeReader<T>, UseSite.Renamed {

    static final ValueReaderFactory INTEGER_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name, new IntegerParser((IntegerType) definition.body()),
                    context.locationOf(name, definition));
    static final ValueReaderFactory TEXT_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name, new TextParser((TextType) definition.body()), context.locationOf(name, definition));
    static final ValueReaderFactory DECIMAL_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name, new DecimalParser((DecimalType) definition.body()),
                    context.locationOf(name, definition));
    static final ValueReaderFactory FLOAT_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name, new FloatParser((FloatType) definition.body()), context.locationOf(name, definition));
    static final ValueReaderFactory RATIONAL_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name, new RationalParser((RationalType) definition.body()),
                    context.locationOf(name, definition));
    static final ValueReaderFactory UUID_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name, new UuidParser((UuidType) definition.body()), context.locationOf(name, definition));
    // The alphabet is not in the body: it is @bytes_encoding's, resolved from this definition and its
    // supertypes, defaulting to base64. A field carrying its own directive overrides it where the record
    // reader wires its children.
    static final ValueReaderFactory BYTES_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name,
                    new BytesParser(BytesEncoding.of(name, definition, context), (BytesType) definition.body()),
                    context.locationOf(name, definition));
    static final ValueReaderFactory DATE_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name, new DateParser((DateType) definition.body()), context.locationOf(name, definition));
    static final ValueReaderFactory TIME_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name, new TimeParser((TimeType) definition.body()), context.locationOf(name, definition));
    static final ValueReaderFactory DATETIME_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name, new DateTimeParser((DateTimeType) definition.body()),
                    context.locationOf(name, definition));
    static final ValueReaderFactory DURATION_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name, new DurationParser((DurationType) definition.body()),
                    context.locationOf(name, definition));
    static final ValueReaderFactory PERIOD_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name, new PeriodParser((PeriodType) definition.body()),
                    context.locationOf(name, definition));
    static final ValueReaderFactory URI_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name, new UriParser((UriType) definition.body()), context.locationOf(name, definition));
    static final ValueReaderFactory REGEX_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name, new RegexParser((RegexType) definition.body()), context.locationOf(name, definition));
    static final ValueReaderFactory MAC_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name, new MacParser((MacType) definition.body()), context.locationOf(name, definition));
    static final ValueReaderFactory EMAIL_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name, new EmailParser((EmailType) definition.body()), context.locationOf(name, definition));
    /**
     * {@code within}/{@code excluding} aren't modeled by {@link Cidr4Parser} -- see its own Javadoc -- but
     * {@code min_prefix}/{@code max_prefix} are, so unlike {@link #IPV4_TYPE} this does read {@code
     * definition}'s own body.
     */
    static final ValueReaderFactory CIDR4_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name, new Cidr4Parser((Cidr4Type) definition.body()), context.locationOf(name, definition));
    /** Same reasoning as {@link #CIDR4_TYPE}, for {@link Cidr6Parser}. */
    static final ValueReaderFactory CIDR6_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name, new Cidr6Parser((Cidr6Type) definition.body()), context.locationOf(name, definition));

    /**
     * {@code complex_type} has nothing to configure ({@code component} is fixed, not modeled -- see {@link
     * ComplexParser}'s own Javadoc), so this ignores {@code definition}'s own body entirely (though not its
     * position) and always wraps the one {@link ComplexParser#UNCONSTRAINED} singleton.
     */
    static final ValueReaderFactory COMPLEX_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name, ComplexParser.UNCONSTRAINED, context.locationOf(name, definition));
    /**
     * {@code within}/{@code excluding} ({@code schema.meta.Ipv4Type}'s own fields) aren't modeled by {@link
     * Ipv4Parser} -- see its own Javadoc -- so, like {@link #COMPLEX_TYPE}, this ignores {@code
     * definition}'s own body.
     */
    static final ValueReaderFactory IPV4_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name, Ipv4Parser.UNCONSTRAINED, context.locationOf(name, definition));
    /** Same reasoning as {@link #IPV4_TYPE}, for {@link Ipv6Parser}. */
    static final ValueReaderFactory IPV6_TYPE = (name, definition, context) ->
            new AtomTypeReader<>(name, Ipv6Parser.UNCONSTRAINED, context.locationOf(name, definition));
    /**
     * The enum reader for both tree and object-binding modes: {@code boolean} reads a real {@code Boolean}
     * ({@link BooleanReader}), every other enum instance its member text ({@link EnumParser}). Dispatch is
     * keyed on the declaration's own name, the same mechanism {@link #UNIT} uses for {@code value}/{@code
     * token}/{@code void}. (Tree mode then wraps the result in a {@code TsonAtom} -- see {@link
     * ValueReaderFactoryRegistry}.)
     */
    static final ValueReaderFactory ENUM_OBJECT_MODE = (name, definition, context) ->
            "boolean".equals(name)
                    ? new BooleanReader(context.locationOf(name, definition))
                    : new AtomTypeReader<>(name, new EnumParser((EnumBody) definition.body()),
                            context.locationOf(name, definition));
    /**
     * {@code unit}'s three real instances -- {@code value}/{@code token}/{@code void} -- all
     * resolve to the identical empty body, so, per the kernel's own doc ("distinguished by name and
     * prose-level parsing contract, not by schema shape"), dispatch here is keyed on the
     * declaration's own name, not its resolved shape. {@code void} doesn't fit {@link AtomType}'s
     * {@code read(TokenValue)} shape at all (its contract admits only the absent sentinel {@code _},
     * not a token), so it bypasses this class entirely via {@link VoidReader}. An unrecognized
     * {@code unit}-constructed name falls back to {@link IdentifierParser}, which validates the name profile.
     */
    static final ValueReaderFactory UNIT = (name, definition, context) -> switch (name) {
        case "void" -> new VoidReader(context.locationOf(name, definition));
        case "value" -> new AtomTypeReader<>(name, ValueParser.INSTANCE, context.locationOf(name, definition));
        default -> new AtomTypeReader<>(name, IdentifierParser.INSTANCE, context.locationOf(name, definition));
    };

    /**
     * The schema entry's own declared name -- the <em>declaration's</em>, not the built-in it refines, so a
     * {@code TYPE_MISMATCH} against {@code my_percentage => !positive_integer ^ { max: 100 }} names {@code
     * my_percentage}, which is what its author wrote and can act on. There is no name on {@link AtomType} to
     * use instead (one {@code IntegerParser} serves {@code int8}..{@code int256} and every refinement of
     * them), so it has to come from the entry, which every {@link ValueReaderFactory} is handed anyway.
     *
     * <p><b>It is not what a constraint violation reports as {@code expected}.</b> Naming the type there
     * says strictly less than the message already does -- a consumer wanting the bound has to recover it by
     * regexing the sentence, which is the one thing {@link Diagnostic}'s structured half exists to avoid.
     * The atom knows the facet it just violated and carries it on the exception; see {@link
     * io.ltr8.tson.compiler.atom.AtomTypeException} for the vocabulary. The name still leads the
     * <em>message</em>, which is where the author needs to see it.
     */
    private final String name;

    private final AtomType<T> delegate;
    private final SchemaLocation schemaLocation;

    /** A reader over an {@link AtomType} chosen by the caller rather than by the declaration's own body. */
    static <T> AtomTypeReader<T> of(String name, AtomType<T> delegate, SchemaLocation schemaLocation) {
        return new AtomTypeReader<>(name, delegate, schemaLocation);
    }

    /**
     * {@inheritDoc} <p>Shares the parser and the location; only the name differs. Built once when a
     * composite reader wires an aliased child, never on a read -- see {@link UseSite}.
     */
    @Override
    public TsonTypeReader<?> renamed(String displayName) {
        return new AtomTypeReader<>(displayName, delegate, schemaLocation);
    }

    /**
     * The same position, read by a different atom and under a different name -- the location is all that
     * survives. {@code RecordBindReader} uses it for a {@code value}-typed slot, whose atom depends on what
     * the bound component holds and so cannot be known when the factory runs. The name goes with it because
     * the entry's own is {@code value}, which names the escape hatch rather than anything the author wrote.
     */
    /**
     * Whether this reads the uninterpreted {@code value} atom -- the one slot {@code RecordBindReader} may
     * specialise to the host type its component holds. False once something already has: {@code tokenAware}
     * claims a {@code Token}-bound slot before the field loop runs, and that is a specialisation of the same
     * kind rather than a case to redo.
     */
    boolean readsUninterpretedValue() {
        return delegate == ValueParser.INSTANCE;
    }

    TsonTypeReader<?> overAtom(String displayName, AtomType<?> replacement) {
        return new AtomTypeReader<>(displayName, replacement, schemaLocation);
    }

    private AtomTypeReader(String name, AtomType<T> delegate, SchemaLocation schemaLocation) {
        this.name = name;
        this.delegate = delegate;
        this.schemaLocation = schemaLocation;
    }

    @Override
    public T read(TsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        EventSkip.annotationsAndTypeRef(ctx);
        TsonEvent e = ctx.peek();
        if (!(e instanceof TokenEvent token)) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected a token for '" + name + "', found "
                    + TypeRefCheck.describe(e), "a token for " + name, TypeRefCheck.describe(e));
            EventSkip.coreValue(ctx);
            return null;
        }
        ctx.next();
        TokenValue tokenValue = new TokenValue(token.text(), token.form());
        try {
            return delegate.read(tokenValue);
        } catch (AtomTypeException ex) {
            ctx.report(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                    "'" + name + "': " + ex.getMessage(), ex.expected(), token.text());
            return null;
        }
    }
}
