package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.ReferenceGrammar;
import io.ltr8.tson.base.diagnostics.RecordDiagnostics;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.compiler.resolver.AliasChain;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.ReferenceBody;
import io.ltr8.tson.schema.meta.SiblingRef;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonAtom;

import java.util.Optional;

/**
 * A dependent field's reader, {@code v?: kind!} ([TSON-SCHEMA] §5.2): the value is read against the type its sibling's
 * value names in the same record instance. This is {@link DeclaredReader}'s dispatch by tag, the tag taken from the
 * sibling instead of from a {@code !} before the value, and it builds nothing of its own: the named type's reader, in
 * the mode the schema compiled in, builds the value.
 *
 * <p><b>Read through its record, never on its own.</b> The record reader ({@link RecordAbstractReader}) knows which
 * sibling value this instance holds -- written, defaulted or pinned -- and hands it to {@link #readAgainst}; {@link
 * #read} is reached only where no record stands around the field, which is an invariant broken.
 *
 * <p><b>The sibling's value names a type a value can be read against</b>, or the value is not read: a sibling
 * reference names a field and no type, a template is no type until it is applied and reading never starts a
 * materialisation, and an application the closure does not hold names nothing. Each is refused naming the sibling,
 * since that is the value the author has to change; the dependent value is skipped unjudged.
 *
 * <p><b>In a schema document the value is held as written.</b> A default or pin is decoded against its field's type
 * once every reader exists and judged when the schema links (a held value orders nothing, [TSON-SCHEMA] §5.2), so the
 * kernel's own {@code record_field.value}, typed {@code type!}, reads the token uninterpreted, as {@code meta.value}
 * would, through a reader of its own rather than that entry's: holding the value waits on nothing, so the kernel's
 * {@code record_field} waits on no {@code meta.value}. A bound {@code Token} component never reaches here ({@code
 * RecordBindReader.tokenAware}).
 */
final class DependentFieldReader implements TsonTypeReader<Object> {

    private final String field;
    private final String sibling;
    private final TsonTypeReaderResolver readers;
    private final ValueReaderContext context;

    /** What a schema document holds the value as: the token, uninterpreted, in the mode the record reads in. */
    private final TsonTypeReader<?> held;

    /**
     * The reference typing the sibling, past its alias hops, which an application the sibling's value writes is
     * resolved through to the entry it denotes ({@link ReferenceBody#entryOf}); null until {@link #forRecord}.
     */
    private final ReferenceBody siblingType;

    DependentFieldReader(String field, String sibling, TsonTypeReaderResolver readers, ValueReaderContext context,
                         TsonTypeReader<?> held) {
        this(field, sibling, readers, context, held, null);
    }

    private DependentFieldReader(String field, String sibling, TsonTypeReaderResolver readers,
                                 ValueReaderContext context, TsonTypeReader<?> held, ReferenceBody siblingType) {
        this.field = field;
        this.sibling = sibling;
        this.readers = readers;
        this.context = context;
        this.held = held;
        this.siblingType = siblingType;
    }

    /** This reader within {@code record}, its sibling's reference found -- what the record reader holds. */
    DependentFieldReader forRecord(RecordBody record) {
        RecordField declared = record.fields().get(sibling);
        if (declared == null || context == null || !(declared.type() instanceof TypeRef type)) {
            return this;
        }
        Top body = context.schema().entries().get(AliasChain.terminal(type.name(), context.schema().entries()::get));
        return new DependentFieldReader(field, sibling, readers, context, held,
                body instanceof ReferenceBody reference ? reference : null);
    }

    /** The field this reader's type is taken from. */
    String sibling() {
        return sibling;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        throw new IllegalStateException("the dependent field '" + field + "' is read through its record, which "
                + "knows the value of its sibling '" + sibling + "'");
    }

    /**
     * The value at {@code ctx}'s cursor read against the type {@code named} names, {@code siblingCtx} being where a
     * refusal of the sibling's value is reported, in {@code rules}' words. {@code named} is null where the sibling's
     * own read was refused, which has been reported: the value is then skipped unjudged.
     */
    Object readAgainst(TsonReadContext ctx, Reference named, TsonReadContext siblingCtx, RecordDiagnostics rules) {
        if (ctx.readsSchemaDocument()) {
            return held.read(ctx);
        }
        if (named == null) {
            TsonReadContext.skipUnjudged(ctx);
            return null;
        }
        Optional<String> refusal = Optional.empty();
        String entry = null;
        switch (named) {
            case SiblingRef reference -> refusal = Optional.of("the sibling reference '" + reference.text()
                    + "', which names a field and no type");
            case TypeRef reference -> {
                entry = entryOf(reference);
                if (entry == null) {
                    refusal = Optional.of("'" + reference.text() + "', which names no type this schema closes");
                } else if (context != null) {
                    Top body = context.schema().entries().get(AliasChain.terminal(entry,
                            context.schema().entries()::get));
                    if (body == null) {
                        refusal = Optional.of("'" + reference.text() + "', which names no type of this schema");
                    } else if (OpenEntry.isOpen(body)) {
                        refusal = Optional.of("'" + reference.text() + "', a template, which no value is read "
                                + "against until it is applied");
                    }
                }
            }
        }
        if (refusal.isPresent()) {
            siblingCtx.report(rules.siblingNamesNoType(sibling, field, refusal.get(), named.text()));
            TsonReadContext.skipUnjudged(ctx);
            return null;
        }
        return readers.resolve(entry).read(ctx);
    }

    /** The entry {@code reference} names: a name is its own, an application the one the closure closed it to. */
    private String entryOf(TypeRef reference) {
        if (reference.arguments().isEmpty()) {
            return reference.name();
        }
        return siblingType == null ? null : siblingType.entryOf(reference).orElse(null);
    }

    /**
     * The reference a sibling's read produced, in whichever form the read built it: a tree's atom, the reference
     * itself, or the canonical text a string component holds; null where the read produced nothing.
     */
    static Reference referenceIn(Object decoded) {
        if (decoded instanceof io.ltr8.tson.tree.TsonValue tree && !(tree instanceof TsonAtom)) {
            return null; // a tree's void or missing node: the read produced no reference
        }
        Object value = decoded instanceof TsonAtom atom ? atom.value() : decoded;
        if (value instanceof io.ltr8.annotation.Annotated<?> boxed) {
            value = boxed.value();
        }
        return switch (value) {
            case null -> null;
            case Reference reference -> reference;
            case CharSequence text -> {
                try {
                    yield ReferenceGrammar.parseReference(text.toString());
                } catch (AtomParseException e) {
                    yield null;
                }
            }
            default -> throw new IllegalStateException("a dependent field's sibling read to a "
                    + value.getClass().getName() + ", which holds no reference");
        };
    }

}
