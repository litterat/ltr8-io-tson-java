package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;

/**
 * A leading {@code $schema} at a position that reads a tag and is not scoped -- a scope the position never
 * admitted, refused identically wherever the leading members are read.
 *
 * <p>Shared because the answer is the position's <em>kind</em> and not its extension: a concrete record, every
 * dispatcher over a record family and a choice all refuse it on the same terms, and a copy per reader would be
 * a chance per reader to disagree.
 */
final class Tags {

    /** How a record position describes itself in the {@code $schema} refusal. */
    static final String RECORD = "is a record";

    /** How a choice position describes itself in the {@code $schema} refusal. */
    static final String CHOICE = "is a choice, whose variants its own schema declares";

    private Tags() {
    }

    /**
     * Whether {@code lead} opens a schema scope here, reporting the refusal and consuming the value when it does
     * -- so a caller told {@code true} is done with the object and returns its own refusal result.
     *
     * @param what how the position describes itself: {@link #RECORD} or {@link #CHOICE}
     */
    static boolean refusesScope(JsonReadContext ctx, ReservedMembers.Lead lead, String displayName, String what) {
        if (!lead.schema()) {
            return false;
        }
        refuseScope(ctx, displayName, what);
        EventSkip.nextValue(ctx);
        return true;
    }

    /**
     * Reports a {@code $schema} in the object at {@code ctx}, consuming nothing. §8.5 admits one exactly where
     * the position's effective type is a {@code scoped} instance holding EXTERN, or a container of one. Neither a
     * record nor a choice is one, and §3.3 makes it a resolver error anywhere else -- a scope change the model
     * never opted into.
     */
    static void refuseScope(JsonReadContext ctx, String displayName, String what) {
        ctx.field(ReservedMembers.SCHEMA).report(Diagnostic.Code.UNRECOGNIZED_FIELD,
                "'$schema' opens a schema scope, which [TSON-SCHEMA] §7.8 admits only at a scoped position "
                        + "-- '" + displayName + "' " + what, "no $schema at this position",
                ReservedMembers.SCHEMA);
    }
}
