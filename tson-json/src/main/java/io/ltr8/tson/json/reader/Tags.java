package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonValue;

/**
 * The two ways an object's reserved members can be wrong at a record-shaped position, refused identically
 * wherever one is scanned -- a {@code $}-initial name outside §3.2's closed set, and a {@code $schema}
 * opening a scope the position never admitted.
 *
 * <p>Shared because the answer is the position's *kind* and not its extension: a concrete record, an abstract
 * base and a sealed one all refuse these on the same terms, and three copies would be three chances to
 * disagree about a set §3.2 closes.
 */
final class Tags {

    private Tags() {
    }

    /**
     * The refusal for a misused reserved member, or {@code null} where there is none and the caller should go
     * on. Consumes the value on refusal, so a caller that gets a value back is done with the object.
     */
    static JsonValue refuseMisuse(JsonReadContext ctx, ReservedMembers.Tag tag, String displayName) {
        if (tag.unknown() != null) {
            ReservedMembers.refuseUnknown(ctx, tag.unknown());
            EventSkip.nextValue(ctx);
            return JsonNull.INSTANCE;
        }
        if (tag.schema()) {
            // §8.5 admits `$schema` exactly where the position's effective type is a `scoped` instance
            // holding EXTERN, or a container of one. A record position is not one, and §3.3 makes it a
            // resolver error anywhere else -- a scope change the model never opted into.
            ctx.field(ReservedMembers.SCHEMA).report(Diagnostic.Code.UNRECOGNIZED_FIELD,
                    "'$schema' opens a schema scope, which [TSON-SCHEMA] §7.8 admits only at a scoped position "
                            + "-- '" + displayName + "' is a record", "no $schema at this position",
                    ReservedMembers.SCHEMA);
            EventSkip.nextValue(ctx);
            return JsonNull.INSTANCE;
        }
        return null;
    }
}
