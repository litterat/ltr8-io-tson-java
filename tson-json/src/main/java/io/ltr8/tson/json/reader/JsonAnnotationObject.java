package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.stream.JsonEvent;

import java.util.List;

/**
 * [TSON-JSON] §3.3's annotation object: the JSON carrier for a type annotation, which TSON text attaches
 * beside a value and JSON has no beside for.
 *
 * <p>Two forms. The <b>wrapper</b> is an object whose members are reserved only, with {@code $value} present
 * -- {@code {"$type": "age", "$value": 42}} -- and carries an annotation for a value of any shape. The
 * <b>inline</b> form lets the reserved members stand beside a record's own when the selected type reads the
 * value as a record, and {@code $value} is absent: {@code {"$type": "employee", "name": "Ada"}}. It exists
 * because wrapping every subtyped record would bury the common case (§1.3, principle 2), and it is
 * unambiguous because §3.2 reserves every {@code $}-initial name and no identifier begins with one.
 *
 * <p><b>Recognising one needs a lookahead, and that is a property of JSON rather than a choice here.</b>
 * §6.1.6 gives member order no meaning, so {@code $type} may sit anywhere in the object and the opening
 * brace settles nothing. {@link #scan} therefore reads member <em>names</em> across the whole object,
 * skipping values without materialising them, and rewinds -- so the read that follows sees a stream nothing
 * has touched. It costs one pass over the object's events, replayed from a buffer rather than re-lexed.
 */
final class JsonAnnotationObject {

    /** §3.2's closed set. Membership is what a {@code $}-initial name is tested against, and there is no fourth. */
    static final String TYPE = "$type";
    static final String VALUE = "$value";
    static final String SCHEMA = "$schema";
    static final List<String> RESERVED = List.of(SCHEMA, TYPE, VALUE);

    private JsonAnnotationObject() {
    }

    /**
     * What an object's member names say about it.
     *
     * @param present  whether any reserved member appeared -- §3.3's recognition test, and §8.3.1's
     * @param type     the {@code $type} member's string content, or null where it was absent or not a string
     * @param wrapper  whether {@code $value} appeared, which is what picks the wrapper form over the inline one
     * @param schema   whether {@code $schema} appeared -- admitted only at a scoped position (§8.5)
     * @param unknown  a {@code $}-initial member outside the closed set, or null -- a resolver error (§3.2)
     */
    record Tag(boolean present, String type, boolean wrapper, boolean schema, String unknown) {

        static final Tag NONE = new Tag(false, null, false, false, null);
    }

    /**
     * Reads {@code ctx}'s object far enough to say whether it is an annotation object, then rewinds.
     *
     * <p>The caller must have established that an {@code ObjectStart} is at the cursor. Nothing is reported
     * from here: a scan is a question, and what a wrong answer means depends on the position that asked.
     */
    static Tag scan(JsonReadContext ctx) {
        return JsonReadContext.lookingAhead(ctx, JsonAnnotationObject::read);
    }

    private static Tag read(JsonReadContext ctx) {
        if (!(ctx.next() instanceof JsonEvent.ObjectStart)) {
            return Tag.NONE;
        }
        boolean present = false;
        boolean wrapper = false;
        boolean schema = false;
        String type = null;
        String unknown = null;
        while (true) {
            JsonEvent event = ctx.next();
            if (event instanceof JsonEvent.ObjectEnd) {
                return present || unknown != null
                        ? new Tag(present, type, wrapper, schema, unknown)
                        : Tag.NONE;
            }
            if (!(event instanceof JsonEvent.MemberName member)) {
                return Tag.NONE;
            }
            String name = member.name();
            if (name.startsWith("$")) {
                switch (name) {
                    case TYPE -> {
                        present = true;
                        type = ctx.peek() instanceof JsonEvent.StringValue string ? string.value() : null;
                    }
                    case VALUE -> {
                        present = true;
                        wrapper = true;
                    }
                    case SCHEMA -> {
                        present = true;
                        schema = true;
                    }
                    // §3.2: the set is closed, on the same terms as the directive name set -- there is no
                    // unknown-reserved-member category and no extension mechanism, so this is an error and
                    // not a member to pass through.
                    default -> unknown = unknown == null ? name : unknown;
                }
            }
            JsonEventSkip.nextValue(ctx);
        }
    }

    /** Whether {@code name} is one this encoding reserves -- true of any {@code $}-initial name, closed set or not. */
    static boolean isReserved(String name) {
        return name.startsWith("$");
    }

    /** Reports a {@code $}-initial member outside §3.2's closed set, which admits no extension. */
    static void refuseUnknown(JsonReadContext ctx, String name) {
        ctx.field(name).report(Diagnostic.Code.UNRECOGNIZED_FIELD,
                "'%s' begins with '$', which this encoding reserves (§3.2), and the reserved set is closed"
                        .formatted(name), String.join(" | ", RESERVED), name);
    }
}
