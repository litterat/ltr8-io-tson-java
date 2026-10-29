package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;

/**
 * A leading {@code $import} at a value position -- the document's binding, which stands at the top of the document
 * alone ([TSON-JSON] §3.3, §3.4) -- refused identically wherever the leading members are read.
 *
 * <p>Shared because the answer is the same at every position: a concrete record, every dispatcher over a record
 * family, a choice and a declared position all refuse it on the same terms, and a copy per reader would be a
 * chance per reader to disagree.
 */
final class Tags {

    /** How a record position describes itself in the {@code $import} refusal. */
    static final String RECORD = "is a record";

    /** How a choice position describes itself in the {@code $import} refusal. */
    static final String CHOICE = "is a choice, whose variants its own schema declares";

    private Tags() {
    }

    /**
     * Whether {@code lead} carries an {@code $import}, reporting the refusal and consuming the value when it does
     * -- so a caller told {@code true} is done with the object and returns its own refusal result.
     *
     * @param what how the position describes itself: {@link #RECORD}, {@link #CHOICE} or the caller's own
     */
    static boolean refusesScope(JsonReadContext ctx, TagMembers.Lead lead, String displayName, String what) {
        if (!lead.imports()) {
            return false;
        }
        refuseScope(ctx, displayName, what);
        EventSkip.nextValue(ctx);
        return true;
    }

    /**
     * Reports an {@code $import} in the object at {@code ctx}, consuming nothing: a resolver error at every value
     * position (§3.3), {@code SCOPE_NOT_ADMITTED} -- the document's binding written where no binding stands, as an
     * {@code !!import} at a value position is a parse error in text.
     */
    static void refuseScope(JsonReadContext ctx, String displayName, String what) {
        ctx.field(TagMembers.IMPORT).report(Diagnostic.Code.SCOPE_NOT_ADMITTED,
                "'$import' binds the document to the schemas it names, which it does at the top of the document "
                        + "alone (§3.3) -- '" + displayName + "' " + what, "no $import at this position",
                TagMembers.IMPORT);
    }
}
