package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;

/**
 * The two ways an object's reserved members can be wrong at a position that reads a tag, refused identically
 * wherever one is scanned -- a {@code $}-initial name outside §3.2's closed set, and a {@code $schema}
 * opening a scope the position never admitted.
 *
 * <p>Shared because the answer is the position's <em>kind</em> and not its extension: a concrete record, every
 * dispatcher over a record family and a choice all refuse these on the same terms, and a copy per reader
 * would be a chance per reader to disagree about a set §3.2 closes.
 */
final class Tags {

    /** How a record position describes itself in the {@code $schema} refusal. */
    static final String RECORD = "is a record";

    /** How a choice position describes itself in the {@code $schema} refusal. */
    static final String CHOICE = "is a choice, whose variants its own schema declares";

    private Tags() {
    }

    /**
     * Whether {@code tag} misuses the reserved namespace, reporting the refusal and consuming the value when
     * it does -- so a caller told {@code true} is done with the object and returns its own refusal result.
     *
     * @param what how the position describes itself: {@link #RECORD} or {@link #CHOICE}
     */
    static boolean refusesMisuse(JsonReadContext ctx, ReservedMembers.Tag tag, String displayName, String what) {
        if (tag.unknown() != null) {
            ReservedMembers.refuseUnknown(ctx, tag.unknown());
            EventSkip.nextValue(ctx);
            return true;
        }
        if (tag.schema()) {
            // §8.5 admits `$schema` exactly where the position's effective type is a `scoped` instance
            // holding EXTERN, or a container of one. Neither a record nor a choice is one, and §3.3 makes it a
            // resolver error anywhere else -- a scope change the model never opted into.
            ctx.field(ReservedMembers.SCHEMA).report(Diagnostic.Code.UNRECOGNIZED_FIELD,
                    "'$schema' opens a schema scope, which [TSON-SCHEMA] §7.8 admits only at a scoped position "
                            + "-- '" + displayName + "' " + what, "no $schema at this position",
                    ReservedMembers.SCHEMA);
            EventSkip.nextValue(ctx);
            return true;
        }
        return false;
    }
}
