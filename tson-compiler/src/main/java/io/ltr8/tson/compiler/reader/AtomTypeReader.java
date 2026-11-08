package io.ltr8.tson.compiler.reader;

import java.util.Optional;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.unicode.IdentifierProfile;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomRefusal;
import io.ltr8.tson.atom.ReferenceGrammar;
import io.ltr8.tson.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ReferenceBody;
import io.ltr8.tson.compiler.atom.RawTokenParser;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.compiler.stream.EventSkip;
import io.ltr8.tson.compiler.stream.ReferenceEvent;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.schema.unicode.IdentifierType;
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
                    definition instanceof ReferenceBody kind && kind.namesTypes() ? kind.of().name() : null,
                    definition instanceof ReferenceBody reference && !reference.namesTypes() ? reference.of().name() : null,
                    AtomParsers.inSchemaDocument(definition).orElse(null),
                    definition instanceof ReferenceBody facet && facet.allowSibling()))
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
     * The {@code of} of a reference that names types, a constructor ({@code meta.ref<record>}'s {@code record}), or {@code
     * null} for every other atom.
     */
    private final String kindOf;

    /** The {@code of} of a reference that names values, a type ({@code meta.ref<method>}'s {@code method}), or null. */
    private final String refOf;

    /**
     * What a kind or a reference reads in a schema document's value ({@link AtomParsers#inSchemaDocument}), or {@code
     * null} for every other atom: the name's grammar alone, the name recorded for the link to judge.
     */
    private final AtomType<T> inSchemaDocument;

    /**
     * Whether this reads a reference whose {@code allow_sibling} admits a sibling reference ({@code kind!}): {@code
     * meta.field_type_ref}, the type of a record field's {@code type}, and nothing else the kernel declares.
     */
    private final boolean admitsSibling;

    /** {@code body}'s profile when it is an identifier family -- its constructor IS-A {@code identifier_type}. */
    private static IdentifierProfile namesProfile(Top body) {
        return body instanceof IdentifierType identifier ? identifier.profile() : null;
    }


    /** Whether this reads a type's name -- a position a {@code TypeRef} target holds by name. */
    boolean readsAKind() {
        return kindOf != null;
    }

    /** Whether a sibling reference is a value here: the reference's {@code allow_sibling} ({@link #admitsSibling}). */
    boolean admitsSibling() {
        return admitsSibling;
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
            return TsonSchema.SlotType.typeName(kindOf, name);
        }
        if (refOf != null) {
            return TsonSchema.SlotType.valueName(refOf, name);
        }
        throw new IllegalStateException("'" + name + "' reads neither a kind nor a reference, so no name is held "
                + "there");
    }

    /** A reader over an {@link AtomType} chosen by the caller rather than by the declaration's own body. */
    static <T> AtomTypeReader<T> of(String name, AtomType<T> delegate, SchemaLocation schemaLocation) {
        return new AtomTypeReader<>(name, delegate, schemaLocation, null, null, null, null, false);
    }

    /**
     * {@inheritDoc} <p>Shares the parser and the location; only the name differs. Built once when a
     * composite reader wires an aliased child, never on a read -- see {@link UseSite}.
     */
    @Override
    public TsonTypeReader<?> renamed(String displayName) {
        return new AtomTypeReader<>(displayName, delegate, schemaLocation, names, kindOf, refOf, inSchemaDocument,
                admitsSibling);
    }

    /**
     * Whether this atom reads the token rather than its text: {@code Token}, which records the spelling §8's resolved
     * form carries. Such a position is already specialised, by {@code tokenAware}, and binding a target over it would
     * hand the family the decoded text and lose the form that was the point of claiming it.
     */
    boolean readsTheTokenItself() {
        return delegate instanceof RawTokenParser;
    }

    /**
     * This position reading into {@code wire}, or empty where the family produces nothing that reaches it
     * (§5.2). The family binds the target once, here, so a read carries no target and the
     * disagreement is a compile-time one -- see {@link AtomType#boundTo}.
     *
     * <p><b>The name is kept.</b> Binding a target changes what the position produces and not what the author wrote,
     * so a diagnostic still names the declaration it came from -- {@code meta.type_name}, not the field that happens
     * to hold one.
     */
    Optional<TsonTypeReader<?>> boundTo(Class<?> wire) {
        return delegate.boundTo(wire).map(bound -> new AtomTypeReader<>(name, bound, schemaLocation, names, kindOf,
                refOf, inSchemaDocument == null ? null : inSchemaDocument.boundTo(wire).orElse(null), admitsSibling));
    }

    @SuppressWarnings("unchecked")
    private AtomTypeReader(String name, AtomType<T> delegate, SchemaLocation schemaLocation,
                           IdentifierProfile names, String kindOf, String refOf, AtomType<?> inSchemaDocument,
                           boolean admitsSibling) {
        this.admitsSibling = admitsSibling;
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
        boolean referenceTyped = kindOf != null || refOf != null;
        String text;
        TokenForm form;
        if (e instanceof TokenEvent token) {
            text = token.text();
            form = token.form();
            if (referenceTyped && form != TokenForm.UNQUOTED && (text.indexOf('<') >= 0 || text.endsWith("!"))) {
                // A quoted token is text, never a reference ([TSON-DATA] §7.4): an application and a sibling
                // reference are written unquoted.
                ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected a reference, found the quoted token \"" + text
                        + "\" -- a quoted token is never a reference; write the reference unquoted",
                        "a reference", "the quoted token \"" + text + "\"");
                ctx.next();
                return null;
            }
        } else if (referenceTyped && e instanceof ReferenceEvent reference) {
            // A reference's tree rolls up into the one value the reference atom reads.
            text = reference.reference().text();
            form = TokenForm.UNQUOTED;
        } else {
            String expected = name == null ? "a token" : "a token for " + name;
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected " + (name == null ? "a token" : "a token for '"
                    + name + "'") + ", found " + TypeRefCheck.describe(e), expected, TypeRefCheck.describe(e));
            EventSkip.coreValue(ctx);
            return null;
        }
        ctx.next();
        TokenValue tokenValue = new TokenValue(text, form);
        if (inSchemaDocument != null && ctx.readsSchemaNames()) {
            // A name in a schema document resolves in the schema being resolved: its grammar is read here, and it
            // is recorded for that schema's link to judge.
            try {
                T value = inSchemaDocument.read(tokenValue.text());
                if (value instanceof io.ltr8.tson.schema.meta.SiblingRef) {
                    // A sibling reference, admitted by the reference's allow_sibling, names a field of the record
                    // holding it and no type: there is no member for the link to judge (§5.2).
                    return value;
                }
                ctx.heldByName(value instanceof io.ltr8.tson.schema.meta.TypeRef reference ? reference
                        : io.ltr8.tson.schema.meta.TypeRef.of(text), slotType(), null);
                return value;
            } catch (UnsupportedOperationException gap) {
                ctx.report(Diagnostic.Code.NOT_IMPLEMENTED, gap.getMessage(), "a value this library can judge", text);
                return null;
            } catch (AtomTypeException ex) {
                AtomRefusal refusal = AtomRefusal.of(ex, text, Object.class);
                refusal = name == null ? refusal : refusal.named(name);
                ctx.report(refusal.code(), refusal.message(), refusal.expected(), refusal.actual());
                return null;
            }
        }
        try {
            // `Token` keeps the token, recording the spelling §8's resolved form carries. Every family is a function
            // of the text alone (§5.1).
            if (delegate instanceof RawTokenParser raw) {
                @SuppressWarnings("unchecked")
                T token = (T) raw.read(tokenValue);
                return token;
            }
            T value = delegate.read(tokenValue.text());
            // Hygiene judges the name the value is, in its type's normalization form, not the spelling -- each name
            // a reference writes, where the value is one.
            if (names == null) {
                return value;
            }
            if (value instanceof io.ltr8.tson.schema.meta.TypeRef reference) {
                for (String each : ReferenceGrammar.names(reference)) {
                    if (ctx.refusesName(each, names)) {
                        return null;
                    }
                }
                return value;
            }
            return ctx.refusesName(value instanceof String named ? named : text, names) ? null : value;
        } catch (UnsupportedOperationException gap) {
            // A value this library cannot judge yet, such as a value named through a FIXED field.
            ctx.report(Diagnostic.Code.NOT_IMPLEMENTED, gap.getMessage(), "a value this library can judge", text);
            return null;
        } catch (AtomTypeException ex) {
            // The entry's own name leads the sentence -- see `name` above -- and AtomRefusal decides the
            // code: §8.1 files a contract rejection apart from a range violation, and this reader is not a
            // second opinion about which is which.
            AtomRefusal refusal = AtomRefusal.of(ex, text, Object.class);
            refusal = name == null ? refusal : refusal.named(name);
            ctx.report(refusal.code(), refusal.message(), refusal.expected(), refusal.actual());
            return null;
        }
    }
}
