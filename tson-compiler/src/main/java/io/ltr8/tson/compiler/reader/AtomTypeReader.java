package io.ltr8.tson.compiler.reader;

import java.util.Optional;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.unicode.IdentifierProfile;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomRefusal;
import io.ltr8.tson.atom.ReferenceGrammar;
import io.ltr8.tson.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ReferenceBody;
import io.ltr8.tson.compiler.atom.TokenAtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.compiler.atom.ValueParser;
import io.ltr8.tson.compiler.stream.EventSkip;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.schema.meta.IdentifierType;
import io.ltr8.tson.schema.meta.Top;

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
 * additionally in {@link #ENUM_OBJECT_MODE}, keyed on the declaration's own name rather than its resolved
 * shape -- see its own note.
 */
final class AtomTypeReader<T> implements TsonTypeReader<T>, UseSite.Renamed {

    /**
     * <b>One factory for every atom family</b>, because the mapping from a resolved body to the parser that
     * reads it is {@link AtomParsers}' and there is no second opinion to have about it. A constant per family doing
     * {@code new XParser((XType) definition)} would be a table restating {@code AtomParsers.forType}
     * entry for entry, and two tables are what lets a family be present in one and missing from the other.
     *
     * <p>{@link ValueReaderFactoryRegistry} registers it under each constructor name: what is shared is the
     * mapping, not the registration, and a name that reaches here with a body no atom parses is a
     * fault rather than an author error -- the registry only routes here for names that are atoms.
     */
    static final ValueReaderFactory ATOM = (name, definition, context) -> AtomParsers
            .forRead(definition, context.linked().enumForm(name))
            .<TsonTypeReader<?>>map(parser -> new AtomTypeReader<>(context.authoredName(name, definition), parser,
                    context.locationOf(name), namesProfile(definition),
                    definition instanceof ReferenceBody kind && kind.namesTypes() ? kind.of() : null,
                    definition instanceof ReferenceBody reference && !reference.namesTypes() ? reference.of() : null,
                    AtomParsers.inSchemaDocument(definition).orElse(null)))
            .orElseThrow(() -> new IllegalStateException(
                    "'" + name + "' is registered as an atom but its body has no parser: " + definition));

    /**
     * The enum reader for both tree and object-binding modes: {@code boolean} reads a real {@code Boolean},
     * every other enum instance its member text. Dispatch is keyed on the declaration's own name -- the one
     * atom that needs it, and the one case {@link #ATOM} cannot serve: an enum body maps to {@link
     * io.ltr8.tson.atom.parser.EnumParser}, which hands back the member's own text, and this one name wants the
     * host value its two members stand for.
     * (Tree mode then wraps the result in a {@code TsonAtom} -- see {@link ValueReaderFactoryRegistry}.)
     *
     * <p><b>The parser is the vocabulary's own</b>, asked for by name through {@link
     * BuiltinTypeVocabulary} -- {@code atom.parser} is unexported, and an index is how a caller reaches one.
     * So {@code boolean} reads to the same host value with a schema and without one, and a token that is
     * neither member is refused as the enum miss it is: {@code ATOM_CONSTRAINT_VIOLATION}, the code every
     * other enum reports.
     */
    static final ValueReaderFactory ENUM_OBJECT_MODE = (name, definition, context) -> "boolean".equals(name)
            ? AtomTypeReader.of(name, BuiltinTypeVocabulary.lookup("boolean").orElseThrow(
                    () -> new IllegalStateException("the built-in vocabulary has no 'boolean'")),
                    context.locationOf(name))
            : ATOM.create(name, definition, context);
    /**
     * {@code void_type}: not a scalar at all -- its contract admits only the void sentinel {@code _}, never a
     * token -- so it bypasses {@link AtomType} via {@link VoidReader}. Keyed on the constructor, so the
     * kernel's {@code void} and core's sibling read alike.
     */
    static final ValueReaderFactory VOID = (name, definition, context) ->
            new VoidReader(context.locationOf(name));

    /**
     * {@code value_type}: decoded by [TSON-DATA] §4 base type resolution, whose §4.4 rule is that a quoted token
     * is a string. That depends on the lexical form, which an {@link AtomType} deliberately cannot see, so
     * {@code AtomParsers} declines it and {@link ValueParser} answers here.
     */
    static final ValueReaderFactory VALUE = (name, definition, context) ->
            new AtomTypeReader<>(name, ValueParser.INSTANCE, context.locationOf(name), null, null, null, null);

    /**
     * The schema entry's own declared name -- the <em>declaration's</em>, not the built-in it refines, so a
     * {@code TYPE_MISMATCH} against {@code my_percentage => !integer ^ { min: 0  max: 100 }} names {@code
     * my_percentage}, which is what its author wrote and can act on. There is no name on {@link AtomType} to
     * use instead (one {@code IntegerParser} serves {@code int8}..{@code int256} and every refinement of
     * them), so it has to come from the entry, which every {@link ValueReaderFactory} is handed anyway.
     *
     * <p><b>{@code null} for a form no author named</b> -- an application nothing declares, expanded to an atom
     * instance ({@link ValueReaderContext#authoredName}). The family's own sentence then stands alone, and the
     * position the diagnostic carries says where.
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

    /**
     * The profile of an identifier family, whose values are names ([TSON-DATA] §8.2), or {@code null} for every
     * other atom. A name the identifier policy refuses is reported and read as nothing, as a refused field name
     * is never looked up.
     */
    private final IdentifierProfile names;

    /**
     * The {@code of} of a reference that names types, a constructor ({@code kind<record>}'s {@code record}), or {@code
     * null} for every other atom.
     */
    private final String kindOf;

    /** The {@code of} of a reference that names values, a type ({@code ref<method>}'s {@code method}), or null. */
    private final String refOf;

    /**
     * What a kind or a reference reads in a schema document's value ({@link AtomParsers#inSchemaDocument}), or {@code
     * null} for every other atom: the name's grammar alone, the name recorded for the link to judge.
     */
    private final AtomType<T> inSchemaDocument;

    /** {@code body}'s profile when it is an identifier family -- its constructor IS-A {@code identifier_type}. */
    private static IdentifierProfile namesProfile(Top body) {
        return body instanceof IdentifierType identifier ? identifier.profile() : null;
    }


    /** Whether this reads a type's name -- a position a {@code TypeRef} target holds by name. */
    boolean readsAKind() {
        return kindOf != null;
    }

    /** Whether this reads a value's qualified name -- held by a {@code Ref} or a {@code TypeRef}. */
    boolean readsARef() {
        return refOf != null;
    }

    /** The type a reference's values are of, for a message: {@code method}. */
    String refOf() {
        return refOf;
    }

    /**
     * The declared type of the slot this reads, for the name read there to be recorded with
     * ({@link io.ltr8.tson.compiler.TsonReadContext#heldByName}): a kind's {@code of} or a reference's {@code T},
     * named as the author wrote it where they did.
     */
    TsonSchema.SlotType slotType() {
        if (kindOf != null) {
            return TsonSchema.SlotType.kind(kindOf, name);
        }
        if (refOf != null) {
            return TsonSchema.SlotType.reference(refOf, name);
        }
        throw new IllegalStateException("'" + name + "' reads neither a kind nor a reference, so no name is held "
                + "there");
    }

    /** A reader over an {@link AtomType} chosen by the caller rather than by the declaration's own body. */
    static <T> AtomTypeReader<T> of(String name, AtomType<T> delegate, SchemaLocation schemaLocation) {
        return new AtomTypeReader<>(name, delegate, schemaLocation, null, null, null, null);
    }

    /**
     * {@inheritDoc} <p>Shares the parser and the location; only the name differs. Built once when a
     * composite reader wires an aliased child, never on a read -- see {@link UseSite}.
     */
    @Override
    public TsonTypeReader<?> renamed(String displayName) {
        return new AtomTypeReader<>(displayName, delegate, schemaLocation, names, kindOf, refOf, inSchemaDocument);
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
     * Whether this atom reads the token rather than its text -- {@code value} under §4.4's rule, and
     * {@code Token}, which records the spelling §8's resolved form carries. Such a position is already
     * specialised, by {@code tokenAware} or by {@link #overAtom}, and binding a target over it would hand
     * the family the decoded text and lose the form that was the point of claiming it.
     */
    boolean readsTheTokenItself() {
        return delegate instanceof TokenAtomType;
    }

    /**
     * The same position, read by a different atom and under a different name -- the location is all that
     * survives. {@code RecordBindReader} uses it for a {@code value}-typed slot, whose atom depends on what
     * the bound component holds and so cannot be known when the factory runs. The name goes with it because
     * the entry's own is {@code value}, which names the escape hatch rather than anything the author wrote.
     */
    TsonTypeReader<?> overAtom(String displayName, AtomType<?> replacement) {
        return new AtomTypeReader<>(displayName, replacement, schemaLocation, null, null, null, null);
    }

    /**
     * This position reading into {@code wire}, or empty where the family produces nothing that reaches it
     * (§5.2). The family binds the target once, here, so a read carries no target and the
     * disagreement is a compile-time one -- see {@link AtomType#boundTo}.
     *
     * <p><b>The name is kept, where {@link #overAtom}'s caller replaces it.</b> Binding a target changes
     * what the position produces and not what the author wrote, so a diagnostic still names the declaration
     * it came from -- {@code type_name}, not the field that happens to hold one. A {@code value} slot is the
     * case that does rename, its entry naming the escape hatch rather than anything in the schema.
     */
    Optional<TsonTypeReader<?>> boundTo(Class<?> wire) {
        return delegate.boundTo(wire).map(bound -> new AtomTypeReader<>(name, bound, schemaLocation, names, kindOf,
                refOf, inSchemaDocument == null ? null : inSchemaDocument.boundTo(wire).orElse(null)));
    }

    @SuppressWarnings("unchecked")
    private AtomTypeReader(String name, AtomType<T> delegate, SchemaLocation schemaLocation,
                           IdentifierProfile names, String kindOf, String refOf, AtomType<?> inSchemaDocument) {
        this.name = name;
        this.delegate = delegate;
        this.inSchemaDocument = (AtomType<T>) inSchemaDocument;
        this.schemaLocation = schemaLocation;
        this.names = names;
        this.kindOf = kindOf;
        this.refOf = refOf;
    }


    @Override
    public T read(TsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        EventSkip.annotationsAndTypeRef(ctx);
        TsonEvent e = ctx.peek();
        if (!(e instanceof TokenEvent token)) {
            String expected = name == null ? "a token" : "a token for " + name;
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected " + (name == null ? "a token" : "a token for '"
                    + name + "'") + ", found " + TypeRefCheck.describe(e), expected, TypeRefCheck.describe(e));
            EventSkip.coreValue(ctx);
            return null;
        }
        if (refOf != null && !ctx.readsSchemaNames()) {
            // What a reference's name reaches is judged where a schema links, and a data document never links.
            ctx.report(Diagnostic.Code.NOT_IMPLEMENTED, "a value at a reference-typed position ('ref<" + refOf
                    + ">') in a data document is not yet read: what its name reaches is judged when a schema links, "
                    + "and a data document is not linked", "a position this library can read", token.text());
            ctx.next();
            return null;
        }
        ctx.next();
        TokenValue tokenValue = new TokenValue(token.text(), token.form());
        if (inSchemaDocument != null && ctx.readsSchemaNames()) {
            // A name in a schema document resolves in the schema being resolved: its grammar is read here, and it
            // is recorded for that schema's link to judge.
            try {
                T value = inSchemaDocument.read(tokenValue.text());
                ctx.heldByName(io.ltr8.tson.schema.meta.TypeRef.of(token.text()), slotType(), null);
                return value;
            } catch (AtomTypeException ex) {
                AtomRefusal refusal = AtomRefusal.of(ex, token.text(), Object.class);
                refusal = name == null ? refusal : refusal.named(name);
                ctx.report(refusal.code(), refusal.message(), refusal.expected(), refusal.actual());
                return null;
            }
        }
        try {
            // A form-sensitive atom keeps the token: `value` is decoded by §4 base type resolution, whose
            // §4.4 rule is that a quoted token is a string, and `Token` records the spelling §8's resolved
            // form carries. Every other family is a function of the text alone (§5.1).
            if (delegate instanceof TokenAtomType) {
                @SuppressWarnings("unchecked")
                TokenAtomType<T> formSensitive = (TokenAtomType<T>) delegate;
                return formSensitive.read(tokenValue);
            }
            T value = delegate.read(tokenValue.text());
            // Hygiene judges the name the value is, in its type's normalization form, not the spelling -- each name
            // an application writes, where a kind's value is one.
            String named = value instanceof String name ? name : token.text();
            TsonReadContext reading = ctx;
            return names != null && (kindOf != null ? ReferenceGrammar.anyRefused(named,
                    each -> reading.refusesName(each, names)) : ctx.refusesName(named, names)) ? null : value;
        } catch (UnsupportedOperationException gap) {
            // A value this library cannot judge yet, such as an application its closure records no entry for.
            ctx.report(Diagnostic.Code.NOT_IMPLEMENTED, gap.getMessage(), "a value this library can judge",
                    token.text());
            return null;
        } catch (AtomTypeException ex) {
            // The entry's own name leads the sentence -- see `name` above -- and AtomRefusal decides the
            // code: §8.1 files a contract rejection apart from a range violation, and this reader is not a
            // second opinion about which is which.
            AtomRefusal refusal = AtomRefusal.of(ex, token.text(), Object.class);
            refusal = name == null ? refusal : refusal.named(name);
            ctx.report(refusal.code(), refusal.message(), refusal.expected(), refusal.actual());
            return null;
        }
    }
}
