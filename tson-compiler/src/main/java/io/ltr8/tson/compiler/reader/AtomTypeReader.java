package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.compiler.atom.TokenAtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.compiler.atom.ValueParser;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.schema.meta.EnumBody;

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

    /**
     * <b>One factory for every atom family</b>, because the mapping from a resolved body to the parser that
     * reads it is {@link AtomParsers}' and there is no second opinion to have about it. Each family used to
     * carry its own constant doing {@code new XParser((XType) definition.body())} -- a table restating
     * {@code AtomParsers.forType} entry for entry, which is how {@code period} came to be missing from one
     * of them and present in the other, so a period-typed field's {@code ~} default was reported as "not a
     * scalar type".
     *
     * <p>{@link ValueReaderFactoryRegistry} still registers it under each constructor name: what collapses
     * is the mapping, not the registration, and a name that reaches here with a body no atom parses is a
     * fault rather than an author error -- the registry only routes here for names that are atoms.
     */
    static final ValueReaderFactory ATOM = (name, definition, context) -> AtomParsers
            .forType(name, definition.body())
            .<TsonTypeReader<?>>map(parser ->
                    new AtomTypeReader<>(name, parser, context.locationOf(name, definition)))
            .orElseThrow(() -> new IllegalStateException(
                    "'" + name + "' is registered as an atom but its body has no parser: " + definition.body()));

    /**
     * The enum reader for both tree and object-binding modes: {@code boolean} reads a real {@code Boolean}
     * ({@link BooleanReader}), every other enum instance its member text. Dispatch is keyed on the
     * declaration's own name, the same mechanism {@link #UNIT} uses for {@code value}/{@code token}/{@code
     * void}, and the one case {@link #ATOM} cannot serve -- an enum body maps to one parser, and this name
     * alone wants a reader that is not an atom at all. (Tree mode then wraps the result in a
     * {@code TsonAtom} -- see {@link ValueReaderFactoryRegistry}.)
     */
    static final ValueReaderFactory ENUM_OBJECT_MODE = (name, definition, context) ->
            "boolean".equals(name)
                    ? new BooleanReader(context.locationOf(name, definition))
                    : ATOM.create(name, definition, context);
    /**
     * {@code unit}'s three real instances -- {@code value}/{@code token}/{@code void} -- all resolve to the
     * identical empty body, so, per the kernel's own doc ("distinguished by name and prose-level parsing
     * contract, not by schema shape"), dispatch is keyed on the declaration's own name rather than its
     * resolved shape. §4.2 makes that dispatch normative.
     *
     * <p><b>Two of the three are this encoding's, not the vocabulary's</b>, which is why they are named here
     * and not left to {@link #ATOM}. {@code void} is not a scalar at all -- its contract admits only the
     * absent sentinel {@code _}, never a token -- so it bypasses {@link AtomType} via {@link VoidReader}.
     * {@code value} is decoded by [TSON-DATA] §4 base type resolution, whose §4.4 rule is that a quoted
     * token is a string: it depends on the lexical form, which an {@link AtomType} deliberately cannot see,
     * so {@code AtomParsers} declines it and {@link ValueParser} answers here. Every other
     * {@code unit}-constructed name is an ordinary identifier and {@link #ATOM} has it.
     */
    static final ValueReaderFactory UNIT = (name, definition, context) -> switch (name) {
        case "void" -> new VoidReader(context.locationOf(name, definition));
        case "value" -> new AtomTypeReader<>(name, ValueParser.INSTANCE, context.locationOf(name, definition));
        default -> ATOM.create(name, definition, context);
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
     * io.ltr8.tson.atom.AtomTypeException} for the vocabulary. The name still leads the
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
     * Whether this reads the uninterpreted {@code value} atom -- the one slot {@code RecordBindReader} may
     * specialise to the host type its component holds. False once something already has: {@code tokenAware}
     * claims a {@code Token}-bound slot before the field loop runs, and that is a specialisation of the same
     * kind rather than a case to redo.
     */
    boolean readsUninterpretedValue() {
        return delegate == ValueParser.INSTANCE;
    }

    /**
     * The same position, read by a different atom and under a different name -- the location is all that
     * survives. {@code RecordBindReader} uses it for a {@code value}-typed slot, whose atom depends on what
     * the bound component holds and so cannot be known when the factory runs. The name goes with it because
     * the entry's own is {@code value}, which names the escape hatch rather than anything the author wrote.
     */
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
            // A form-sensitive atom keeps the token: `value` is decoded by §4 base type resolution, whose
            // §4.4 rule is that a quoted token is a string, and `Token` records the spelling §8's resolved
            // form carries. Every other family is a function of the text alone (§5.1).
            if (delegate instanceof TokenAtomType) {
                @SuppressWarnings("unchecked")
                TokenAtomType<T> formSensitive = (TokenAtomType<T>) delegate;
                return formSensitive.read(tokenValue);
            }
            return delegate.read(tokenValue.text());
        } catch (AtomTypeException ex) {
            ctx.report(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                    "'" + name + "': " + ex.getMessage(), ex.expected(), token.text());
            return null;
        }
    }
}
