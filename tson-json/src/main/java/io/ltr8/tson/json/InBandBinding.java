package io.ltr8.tson.json;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.stream.JsonEvent;

/**
 * [TSON-JSON] §3.4's in-band route, as far as this processor reads it: a root annotation object led by {@code
 * $import} -- the document naming the schemas it is read under, as a TSON text document's {@code !!import}s do -- is
 * recognised and declined as {@code NOT_IMPLEMENTED}, the route being one this processor does not yet read.
 *
 * <p>Declined at the root rather than left to the root's reader, which would refuse the member as it refuses an
 * {@code $import} at every value position ({@code SCOPE_NOT_ADMITTED}): at the top of the document the member stands
 * where §3.3 puts it, so refusing it there would be a verdict that changes once the route is read, which is a gap's
 * mark and not a document's fault.
 */
final class InBandBinding {

    private static final String IMPORT = "$import";

    private InBandBinding() {
    }

    /**
     * Whether the root at {@code ctx}'s cursor leads with {@code $import}, reporting the gap when it does. Consumes
     * nothing either way: a caller told {@code true} abandons the read.
     */
    static boolean declines(JsonReadContext ctx) {
        if (!(ctx.peek() instanceof JsonEvent.ObjectStart)) {
            return false;
        }
        boolean leads = JsonReadContext.lookingAhead(ctx, ahead -> {
            ahead.next();   // ObjectStart
            return ahead.next() instanceof JsonEvent.MemberName member && IMPORT.equals(member.name());
        });
        if (leads) {
            ctx.field(IMPORT).report(Diagnostic.Code.NOT_IMPLEMENTED,
                    "the document names its own binding with '$import' ([TSON-JSON] §3.4's in-band route), which "
                            + "this processor does not read -- supply the schema and root type out of band",
                    "a document bound out of band", IMPORT);
        }
        return leads;
    }
}
