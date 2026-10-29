package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;

/**
 * A leading {@code $schema} at a value position -- the document's schema binding, which stands on the root's
 * annotation object alone ([TSON-JSON] §3.4) -- refused identically wherever the leading members are read.
 *
 * <p>Shared because the answer is the same at every position: a concrete record, every dispatcher over a record
 * family, a choice and a declared position all refuse it on the same terms, and a copy per reader would be a
 * chance per reader to disagree.
 */
final class Tags {

    /** How a record position describes itself in the {@code $schema} refusal. */
    static final String RECORD = "is a record";

    /** How a choice position describes itself in the {@code $schema} refusal. */
    static final String CHOICE = "is a choice, whose variants its own schema declares";

    private Tags() {
    }

    /**
     * Whether {@code lead} carries a {@code $schema}, reporting the refusal and consuming the value when it does
     * -- so a caller told {@code true} is done with the object and returns its own refusal result.
     *
     * @param what how the position describes itself: {@link #RECORD}, {@link #CHOICE} or the caller's own
     */
    static boolean refusesScope(JsonReadContext ctx, TagMembers.Lead lead, String displayName, String what) {
        if (!lead.schema()) {
            return false;
        }
        refuseScope(ctx, displayName, what);
        EventSkip.nextValue(ctx);
        return true;
    }

    /**
     * Reports a {@code $schema} in the object at {@code ctx}, consuming nothing: a resolver error at every value
     * position (§3.3), {@code SCOPE_NOT_ADMITTED} -- the document's binding written where no binding stands.
     */
    static void refuseScope(JsonReadContext ctx, String displayName, String what) {
        ctx.field(TagMembers.SCHEMA).report(Diagnostic.Code.SCOPE_NOT_ADMITTED,
                "'$schema' binds the document's schema, which it does on the root's annotation object alone "
                        + "(§3.4) -- '" + displayName + "' " + what, "no $schema at this position",
                TagMembers.SCHEMA);
    }
}
