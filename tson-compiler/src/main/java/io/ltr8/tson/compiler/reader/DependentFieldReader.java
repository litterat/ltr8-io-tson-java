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

import java.util.function.Function;

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
 * since that is the value the author has to change; the dependent value is skipped unjudged. The rule is {@link
 * #typeNamed}, which a value's qualified name going on through a dependent field is judged by too ({@code
 * ValuesByName}).
 *
 * <p><b>In a schema document the value is held as written</b>, whatever its form -- a token, a record, a list, a map,
 * an annotated or tagged value -- as the schemaless tree it is ({@link SchemalessTreeReader#preserving()}), and so it
 * is wherever names resolve in the document being read ({@link TsonReadContext#readsSchemaNames()}), a resolved form
 * read as data among them. The type its sibling names is one of that document's namespace, which the readers of the
 * layer governing it do not hold, so a schema's value is judged when the schema links, against that type, as a
 * default or pin is (a held value orders nothing, [TSON-SCHEMA] §5.2): the record reader hands its events to the
 * read's sink ({@link TsonReadContext#heldAsWritten}). Holding the value waits on nothing, so the kernel's {@code
 * record_field}, whose {@code value} is typed {@code type!}, waits on no {@code meta.value}. A bound {@code Token}
 * component never reaches here ({@code RecordBindReader.tokenAware}), though its record hands the value on the same.
 */
public final class DependentFieldReader implements TsonTypeReader<Object> {

    private final String field;
    private final String sibling;
    private final TsonTypeReaderResolver readers;
    private final ValueReaderContext context;

    /** What a schema document holds the value as: the schemaless tree, its type-refs kept as written. */
    private static final SchemalessTreeReader HELD = SchemalessTreeReader.preserving();

    /**
     * The reference typing the sibling, past its alias hops, which an application the sibling's value writes is
     * resolved through to the entry it denotes ({@link ReferenceBody#entryOf}); null until {@link #forRecord}.
     */
    private final ReferenceBody siblingType;

    DependentFieldReader(String field, String sibling, TsonTypeReaderResolver readers, ValueReaderContext context) {
        this(field, sibling, readers, context, null);
    }

    private DependentFieldReader(String field, String sibling, TsonTypeReaderResolver readers,
                                 ValueReaderContext context, ReferenceBody siblingType) {
        this.field = field;
        this.sibling = sibling;
        this.readers = readers;
        this.context = context;
        this.siblingType = siblingType;
    }

    /** This reader within {@code record}, its sibling's reference found -- what the record reader holds. */
    DependentFieldReader forRecord(RecordBody record) {
        RecordField declared = record.fields().get(sibling);
        if (declared == null || context == null) {
            return this;
        }
        return new DependentFieldReader(field, sibling, readers, context,
                siblingType(declared, context.schema().entries()::get));
    }

    /**
     * The reference typing the sibling {@code declared}, past its alias hops in {@code entries}: what an application
     * the sibling's value writes resolves through ({@link #typeNamed}). Null where the sibling is typed by no
     * reference, which the linker refuses.
     */
    public static ReferenceBody siblingType(RecordField declared, Function<String, ? extends Top> entries) {
        if (!(declared.type() instanceof TypeRef type)) {
            return null;
        }
        return entries.apply(AliasChain.terminal(type.name(), entries)) instanceof ReferenceBody reference
                ? reference : null;
    }

    /**
     * What a sibling holding {@code named} types its dependent field by: the entry a value is read against, or why
     * there is none. {@code siblingType} resolves an application ({@link #siblingType}), and {@code entries} is the
     * namespace the entry is found in, null where none is known and the entry is taken as named.
     */
    public static TypeNamed typeNamed(Reference named, ReferenceBody siblingType,
                                      Function<String, ? extends Top> entries) {
        return switch (named) {
            case SiblingRef reference -> new TypeNamed.None("the sibling reference '" + reference.text()
                    + "', which names a field and no type");
            case TypeRef reference -> {
                String entry = reference.arguments().isEmpty() ? reference.name()
                        : siblingType == null ? null : siblingType.entryOf(reference).orElse(null);
                if (entry == null) {
                    yield new TypeNamed.None("'" + reference.text() + "', which names no type this schema closes");
                }
                Top body = entries == null ? null : entries.apply(AliasChain.terminal(entry, entries));
                if (entries != null && body == null) {
                    yield new TypeNamed.None("'" + reference.text() + "', which names no type of this schema");
                }
                if (body != null && OpenEntry.isOpen(body)) {
                    yield new TypeNamed.None("'" + reference.text() + "', a template, which no value is read "
                            + "against until it is applied");
                }
                yield new TypeNamed.Entry(entry);
            }
        };
    }

    /** What a sibling's value types its dependent field by ({@link #typeNamed}). */
    public sealed interface TypeNamed {

        /** The entry the dependent value is read against. */
        record Entry(String name) implements TypeNamed {
        }

        /**
         * No type a value can be read against: {@code held} says what the sibling holds, in the words {@link
         * RecordDiagnostics#siblingNamesNoType} takes.
         */
        record None(String held) implements TypeNamed {
        }
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
        if (ctx.readsSchemaNames()) {
            return HELD.read(ctx);
        }
        if (named == null) {
            TsonReadContext.skipUnjudged(ctx);
            return null;
        }
        return switch (typeNamed(named, siblingType, context == null ? null : context.schema().entries()::get)) {
            case TypeNamed.Entry entry -> readers.resolve(entry.name()).read(ctx);
            case TypeNamed.None none -> {
                siblingCtx.report(rules.siblingNamesNoType(sibling, field, none.held(), named.text()));
                TsonReadContext.skipUnjudged(ctx);
                yield null;
            }
        };
    }

    /**
     * The reference a sibling's read produced, in whichever form the read built it: a tree's atom, the reference
     * itself, a token held as written, a host's {@code Ref}, or the canonical text a string component holds; null
     * where the read produced nothing.
     */
    public static Reference referenceIn(Object decoded) {
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
            case io.ltr8.tson.schema.meta.Token token -> referenceIn(token.text());
            case io.ltr8.tson.schema.meta.Ref<?> ref -> referenceIn(ref.name());
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
