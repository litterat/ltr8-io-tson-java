package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.stream.JsonEvent;

import io.ltr8.tson.base.unicode.Nfc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [TSON-JSON] §3.2's reserved member namespace, and the peek that asks which of it leads an object.
 *
 * <p><b>The spec calls the construct an "annotation object" (§3.3) and this class does not</b>, because in
 * this codebase {@code Annotation} means an {@code @name} annotation and nothing else -- two dozen types say
 * so, from the {@code tson-annotation} module through {@code Annotations}, {@code TsonAnnotation} and the
 * {@code AnnotationStart}/{@code AnnotationEnd} events. Those have no JSON carrier at all: §4.3 declines one
 * for v1 and makes encoding a value that carries them an encode error. A class named for §3.3 would be the
 * one place the word meant something else, so it is named for the namespace it reads and cites §3.3
 * throughout.
 *
 * <p>What §3.3 defines is the JSON carrier for a type annotation and a schema scope -- what TSON text
 * attaches beside a value, and JSON has no beside for.
 *
 * <p>Two forms. The <b>wrapper</b> is an object whose members are reserved only, with {@code $value} present
 * -- {@code {"$type": "age", "$value": 42}} -- and carries an annotation for a value of any shape. The
 * <b>inline</b> form lets the reserved members stand beside a record's own when the selected type reads the
 * value as a record, and {@code $value} is absent: {@code {"$type": "employee", "name": "Ada"}}.
 *
 * <p><b>The selectors lead, so a peek at the opening members answers the question.</b> §3.3 puts {@code
 * $schema} first where present and {@code $type} after it, and §6.1.5 puts a sealed position's discriminators
 * next. {@link #lead} reads those members and no others, rewinding so the read that follows sees a stream
 * nothing has touched -- what it holds is bounded by the schema, never by the document (§10.1). A valid
 * wrapper's only members are the reserved ones, so its {@code $value} directly follows them, and the peek
 * sees it there; a {@code $value} anywhere else is an extra member of an invalid object, which whoever reads
 * the object refuses. A selector's value is held only when it is a scalar: one that is not stops the peek,
 * so a sender cannot make it hold an arbitrary value.
 */
final class ReservedMembers {

    /** §3.2's closed set. Membership is what a {@code $}-initial name is tested against, and there is no fourth. */
    static final String TYPE = "$type";
    static final String VALUE = "$value";
    static final String SCHEMA = "$schema";
    static final List<String> RESERVED = List.of(SCHEMA, TYPE, VALUE);

    private ReservedMembers() {
    }

    /**
     * What an object's leading members say about it.
     *
     * @param schema    whether {@code $schema} leads -- admitted only at a scoped position (§8.5)
     * @param typed     whether a {@code $type} member leads, whatever its value
     * @param type      that member's string content, or null where it is absent or not a string
     * @param wrapper   whether {@code $value} follows the reserved members -- §3.3's wrapper form
     * @param selectors the scalar value of each requested member found leading after the reserved ones, by
     *                  NFC name; a requested member not among them is absent here
     */
    record Lead(boolean schema, boolean typed, String type, boolean wrapper, Map<String, JsonEvent> selectors) {

        static final Lead NONE = new Lead(false, false, null, false, Map.of());

        /** Whether the object leads with a reserved member -- §3.3's recognition test, and §8.3.1's. */
        boolean present() {
            return schema || typed || wrapper;
        }
    }

    /** The leading reserved members of the object at {@code ctx}'s cursor, which must be an {@code ObjectStart}. */
    static Lead lead(JsonReadContext ctx) {
        return lead(ctx, Set.of());
    }

    /**
     * {@link #lead}, additionally capturing the members named in {@code wanted} where they lead after the
     * reserved ones, in any order among themselves -- a sealed position's discriminators (§6.1.5).
     *
     * <p>Nothing is reported from here: a peek is a question, and what a wrong answer means depends on the
     * position that asked. The names in {@code wanted} are NFC-normalised, as every member-name comparison in
     * this encoding is ([TSON-DATA] §2.5).
     */
    static Lead lead(JsonReadContext ctx, Set<String> wanted) {
        return JsonReadContext.lookingAhead(ctx, ahead -> readLead(ahead, wanted));
    }

    private static Lead readLead(JsonReadContext ctx, Set<String> wanted) {
        if (!(ctx.next() instanceof JsonEvent.ObjectStart)) {
            return Lead.NONE;
        }
        boolean schema = false;
        boolean typed = false;
        String type = null;
        JsonEvent event = ctx.next();
        if (named(event, SCHEMA)) {
            schema = true;
            if (!skipScalar(ctx)) {
                return new Lead(true, false, null, false, Map.of());
            }
            event = ctx.next();
        }
        if (named(event, TYPE)) {
            typed = true;
            type = ctx.peek() instanceof JsonEvent.StringValue string ? string.value() : null;
            if (!skipScalar(ctx)) {
                return new Lead(schema, true, null, false, Map.of());
            }
            event = ctx.next();
        }
        if (named(event, VALUE)) {
            return new Lead(schema, typed, type, true, Map.of());
        }
        Map<String, JsonEvent> selectors = wanted.isEmpty() ? Map.of() : new LinkedHashMap<>();
        while (selectors.size() < wanted.size() && event instanceof JsonEvent.MemberName member) {
            String name = Nfc.of(member.name());
            JsonEvent value = ctx.peek();
            if (!wanted.contains(name) || selectors.containsKey(name) || !isScalar(value)) {
                break;
            }
            selectors.put(name, value);
            ctx.next();
            event = ctx.next();
        }
        return new Lead(schema, typed, type, false, selectors);
    }

    private static boolean named(JsonEvent event, String name) {
        return event instanceof JsonEvent.MemberName member && name.equals(member.name());
    }

    /** Steps past a scalar member value, answering false -- and consuming nothing -- where it is not one. */
    private static boolean skipScalar(JsonReadContext ctx) {
        if (!isScalar(ctx.peek())) {
            return false;
        }
        ctx.next();
        return true;
    }

    private static boolean isScalar(JsonEvent event) {
        return event instanceof JsonEvent.StringValue || event instanceof JsonEvent.NumberValue
                || event instanceof JsonEvent.BooleanValue || event instanceof JsonEvent.NullValue;
    }

    /**
     * §3.3's wrapper form, read: the annotated value is the {@code $value} member, read at {@code target} --
     * the reader for whatever {@code $type} selected. "In wrapper form, any member other than the three
     * reserved names is a resolver error -- the wrapper is apparatus, not a record, and admits nothing else."
     *
     * <p>Member order carries no meaning here either (§6.1.6), so this walks the object rather than assuming
     * {@code $value} last, and reads the value where it is found.
     *
     * <p>Shared by every position that can be tagged -- a record under subsumption (§6.1.5) and a choice
     * variant (§8.1) -- because the wrapper is one form and a second reading of it is a second chance to
     * disagree about what it admits. Returns what {@code target} produced, or null where there was no
     * {@code $value} to read: the wrapper builds nothing of its own, so it serves every mode.
     */
    static Object readWrapped(JsonReadContext ctx, JsonTypeReader<?> target) {
        ctx.next();   // ObjectStart
        return walkWrapper(ctx, target, SCHEMA, false, null);
    }

    /**
     * The rest of a wrapper whose leading members have been read and whose {@code $value} name was just
     * consumed: the value, read at {@code target}, and then whatever follows it, which a wrapper admits none of.
     * For a record reader that met {@code $value} straight after its leading {@code $type}.
     */
    static Object readWrappedValue(JsonReadContext ctx, JsonTypeReader<?> target) {
        return walkWrapper(ctx, target, null, true, target.read(ctx.field(VALUE)));
    }

    /**
     * The wrapper's member loop, from wherever the caller left it.
     *
     * @param next  the leading member that may still come -- {@code $schema}, then {@code $type} (§3.3) --
     *              or null once another member has come
     * @param found whether {@code $value} has already been read, {@code value} being what it read
     */
    private static Object walkWrapper(JsonReadContext ctx, JsonTypeReader<?> target, String next, boolean found,
                                      Object value) {
        while (true) {
            JsonEvent event = ctx.next();
            if (event instanceof JsonEvent.ObjectEnd) {
                if (!found) {
                    ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                            "this is an annotation object in wrapper form and carries no '$value' to annotate",
                            "a '$value' member", "no $value");
                }
                return value;
            }
            if (!(event instanceof JsonEvent.MemberName member)) {
                throw new IllegalStateException("a member name or '}' was due and the stream produced " + event);
            }
            String name = member.name();
            if (VALUE.equals(name)) {
                value = target.read(ctx.field(VALUE));
                found = true;
                continue;
            }
            // The leading members were judged before this was reached; one found here, after them, is out of
            // place, and a `$`-initial name outside the closed set is refused wherever it stands.
            boolean leading = !found && next != null && (name.equals(next) || TYPE.equals(name));
            next = leading && SCHEMA.equals(name) ? TYPE : null;
            if (SCHEMA.equals(name) || TYPE.equals(name)) {
                if (!leading) {
                    refuseMisplaced(ctx, name);
                }
            } else if (isReserved(name)) {
                refuseUnknown(ctx, name);
            } else {
                ctx.field(member.name()).report(Diagnostic.Code.UNRECOGNIZED_FIELD,
                        "'%s' stands beside '$value' in an annotation object, which is apparatus and not a record "
                                .formatted(member.name()) + "-- it admits the reserved members and nothing else "
                                + "(§3.3)", String.join(" | ", RESERVED), member.name());
            }
            EventSkip.nextValue(ctx.field(member.name()));
        }
    }

    /** Whether {@code name} is one this encoding reserves -- true of any {@code $}-initial name, closed set or not. */
    static boolean isReserved(String name) {
        return name.startsWith("$");
    }

    /**
     * Reports a {@code $schema} or {@code $type} that does not lead its object (§3.3) -- a resolver error, the
     * selectors having a fixed place so that no decoder holds more than the schema bounds before dispatch.
     */
    static void refuseMisplaced(JsonReadContext ctx, String name) {
        ctx.field(name).report(Diagnostic.Code.UNRECOGNIZED_FIELD,
                "'%s' must lead its object -- '$schema' first where present, then '$type' (§3.3) -- and here it "
                        .formatted(name) + "follows another member", "'" + name + "' as a leading member", name);
    }

    /** Reports a {@code $}-initial member outside §3.2's closed set, which admits no extension. */
    static void refuseUnknown(JsonReadContext ctx, String name) {
        ctx.field(name).report(Diagnostic.Code.UNRECOGNIZED_FIELD,
                "'%s' begins with '$', which this encoding reserves (§3.2), and the reserved set is closed"
                        .formatted(name), String.join(" | ", RESERVED), name);
    }
}
