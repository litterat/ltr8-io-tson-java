package io.ltr8.tson.json.reader;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.ReferenceGrammar;
import io.ltr8.tson.base.diagnostics.RecordDiagnostics;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.ReferenceBody;
import io.ltr8.tson.schema.meta.SiblingRef;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;

/**
 * A dependent field's reader, {@code v?: kind!} ([TSON-SCHEMA] §5.2, [TSON-JSON] §6.1): the member is read against the
 * type its sibling's value names in the same object, through that type's reader in the mode the schema compiled in.
 * {@code tson-compiler}'s {@code DependentFieldReader} is the same rule over that encoding's readers.
 *
 * <p>Read through its record ({@link RecordReader}), which hands it the sibling's value: the member written before
 * it, the sibling's default where it is not written, or its pin. A sibling naming no type a value can be read against
 * -- a sibling reference, a template, an application the closure does not hold -- is refused at the sibling, and the
 * member is skipped unjudged.
 */
final class DependentReader implements JsonTypeReader<Object> {

    private final String field;
    private final String sibling;
    private final ValueReaderContext context;

    /** The reference typing the sibling, past its alias hops, which resolves an application to its entry; or null. */
    private final ReferenceBody siblingType;

    DependentReader(String field, String sibling, ValueReaderContext context, ReferenceBody siblingType) {
        this.field = field;
        this.sibling = sibling;
        this.context = context;
        this.siblingType = siblingType;
    }

    @Override
    public Object read(JsonReadContext ctx) {
        throw new IllegalStateException("the dependent field '" + field + "' is read through its record, which "
                + "knows the value of its sibling '" + sibling + "'");
    }

    /**
     * The member at {@code ctx}'s cursor read against the type {@code named} names, a refusal of the sibling's value
     * reported at {@code siblingCtx} in {@code rules}' words; {@code named} null where the sibling's own read was
     * refused, which has been reported, and the member is skipped.
     */
    Object readAgainst(JsonReadContext ctx, Reference named, JsonReadContext siblingCtx, RecordDiagnostics rules) {
        if (named == null) {
            EventSkip.nextValue(ctx);
            return null;
        }
        String refusal = null;
        String entry = null;
        switch (named) {
            case SiblingRef reference -> refusal = "the sibling reference '" + reference.text()
                    + "', which names a field and no type";
            case TypeRef reference -> {
                entry = reference.arguments().isEmpty() ? reference.name()
                        : siblingType == null ? null : siblingType.entryOf(reference).orElse(null);
                Top body = entry == null ? null
                        : AliasChain.terminal(context.schema(), entry).map(AliasChain.Resolved::definition).orElse(null);
                if (body == null) {
                    refusal = "'" + reference.text() + "', which names no type this schema closes";
                } else if (OpenEntry.isOpen(body)) {
                    refusal = "'" + reference.text() + "', a template, which no value is read against until it is "
                            + "applied";
                }
            }
        }
        if (refusal != null) {
            siblingCtx.report(rules.siblingNamesNoType(sibling, field, refusal, named.text()));
            EventSkip.nextValue(ctx);
            return null;
        }
        return context.readers().resolve(entry).read(ctx);
    }

    /** A sibling's value as JSON holds it, a string spelling the reference, or null where it spells none. */
    static Reference referenceIn(String text) {
        if (text == null) {
            return null;
        }
        try {
            return ReferenceGrammar.parseReference(text);
        } catch (AtomParseException e) {
            return null;
        }
    }
}
