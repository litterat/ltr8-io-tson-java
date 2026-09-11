package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.unicode.Nfc;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonObject;
import io.ltr8.tson.json.tree.JsonValue;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A record as a JSON object: [TSON-JSON] §6.1. One member per present field, member name = field name, member
 * value read at the field's declared type. The encoding adds only the member mapping; everything
 * [TSON-SCHEMA] says about records binds unchanged, and this is where those rules meet JSON-shaped data.
 *
 * <p><b>Member order carries no meaning</b> (§6.1.6), so this reads members as they arrive and settles every
 * question of presence in a second pass once the object has closed. That second pass is also the only place
 * a field the document never mentioned can be noticed -- it has no event of its own -- which is why the
 * object's opening position is pinned for it ({@link JsonReadContext#withPosition}).
 *
 * <p><b>No rest field.</b> §6.2's {@code @rest} flatten is deliberately not implemented: a member matching no
 * declared field is §6.1.1's closure error, full stop. That is the stricter reading and the correct one until
 * a consumer has shown what the directive's stated shape has to survive.
 */
final class TreeRecordReader implements JsonTypeReader<JsonValue> {

    static final ValueReaderFactory FACTORY = (name, definition, context) -> {
        RecordBody body = (RecordBody) definition.body();
        return new TreeRecordReader(name, body, definition.subtypes(), context,
                context.locationOf(name, definition));
    };

    private final String name;
    private final List<RecordField> fields;
    private final Map<String, Integer> index;
    private final List<JsonTypeReader<?>> readers;
    private final List<FieldValue> stated;
    private final List<FieldGroup> groups;
    private final String declaredFields;
    private final JsonSchemaLocation schemaLocation;

    /** The names §6.1.5 admits at this position besides this entry's own: its subtypes, under [TSON-SCHEMA] §7.2. */
    private final Set<String> subtypes;

    /** How a named subtype's reader is reached at read time -- rebound to the finished schema by the compile. */
    private final TypeReaderResolver readerFor;

    private TreeRecordReader(String name, RecordBody body, Collection<String> subtypes,
                             ValueReaderContext context, JsonSchemaLocation schemaLocation) {
        this.subtypes = Set.copyOf(subtypes);
        this.readerFor = context.readers();
        this.name = name;
        this.fields = List.copyOf(body.fields());
        this.groups = List.copyOf(body.groups());
        this.schemaLocation = schemaLocation;
        Map<String, Integer> byName = new LinkedHashMap<>();
        List<JsonTypeReader<?>> built = new ArrayList<>(fields.size());
        List<FieldValue> values = new ArrayList<>(fields.size());
        for (int i = 0; i < fields.size(); i++) {
            RecordField field = fields.get(i);
            // §6.1.1: member names are NFC-normalized before matching, per [TSON-DATA] §7.2.1's resolver rule.
            byName.put(Nfc.of(field.name()), i);
            built.add(context.readers().resolve(field.type().name()));
            values.add(field.value()
                    .map(token -> FieldValue.of(context.schema(), field.type().name(), token))
                    .orElse(null));
        }
        this.index = Map.copyOf(byName);
        this.readers = List.copyOf(built);
        this.stated = new ArrayList<>(values);
        this.declaredFields = fields.stream().map(RecordField::name).reduce((a, b) -> a + " | " + b).orElse("");
    }

    @Override
    public JsonValue read(JsonReadContext ctx) {
        ctx = ctx.inRecord(schemaLocation);
        if (ctx.peek() instanceof JsonEvent.ObjectStart) {
            ReservedMembers.Tag tag = ReservedMembers.scan(ctx);
            if (tag.present() || tag.unknown() != null) {
                return tagged(ctx, tag);
            }
        }
        return readObject(ctx);
    }

    /** The object itself, reserved members already judged -- §6.1's whole reading, tag or no tag. */
    private JsonValue readObject(JsonReadContext ctx) {
        JsonEvent first = ctx.next();
        if (!(first instanceof JsonEvent.ObjectStart)) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "'%s' is a record, which takes a JSON object, and this is %s"
                    .formatted(name, JsonAtoms.describe(first)), "a JSON object", JsonAtoms.describe(first));
            EventSkip.value(ctx, first);
            return JsonNull.INSTANCE;
        }
        JsonValue[] values = new JsonValue[fields.size()];
        boolean[] seen = new boolean[fields.size()];
        readMembers(ctx, values, seen);
        fillAbsent(ctx.withPosition(first.position()), values, seen);
        validateGroups(ctx.withPosition(first.position()), seen);
        return assemble(values);
    }

    /**
     * The decoded object, in <b>declaration order</b> rather than arrival order.
     *
     * <p>§6.1.6 gives member order no meaning, so a decoder is free to choose one -- and choosing the
     * schema's makes that freedom observable: two documents differing only in member order decode to one
     * tree, which is what "order is presentation" has to mean if anything downstream compares decoded
     * output. It is also the order §6.1.6 asks an encoder for, so a tree written straight back out is
     * already in it, and an injected default lands where its field is declared instead of appended after
     * everything the document happened to state.
     */
    private JsonObject assemble(JsonValue[] values) {
        Map<String, JsonValue> members = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            if (values[i] != null) {
                members.put(Nfc.of(fields.get(i).name()), values[i]);
            }
        }
        return new JsonObject(members);
    }

    /**
     * An object the scan found reserved members on: [TSON-JSON] §3.3's annotation object, at a record
     * position, which §6.1.5 makes the JSON spelling of {@code !employee} at a {@code person} field.
     *
     * <p><b>A tag is never wrong</b> (§8.1) -- a redundant {@code $type} restating this position's own type
     * is admitted and changes nothing. What it may not do is name a type this position does not admit:
     * {@code $type} MUST resolve and MUST be admissible under [TSON-SCHEMA] §7.2, so it names this entry or
     * one of its subtypes and nothing else. The value then validates against the selected type in full.
     */
    private JsonValue tagged(JsonReadContext ctx, ReservedMembers.Tag tag) {
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
                            + "-- '" + name + "' is a record", "no $schema at this position",
                    ReservedMembers.SCHEMA);
            EventSkip.nextValue(ctx);
            return JsonNull.INSTANCE;
        }
        if (tag.type() == null) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                    "this object carries this encoding's reserved members but no '$type' naming a type (§3.3)",
                    "a '$type' member holding a type name", "no $type");
            EventSkip.nextValue(ctx);
            return JsonNull.INSTANCE;
        }
        if (!name.equals(tag.type()) && !subtypes.contains(tag.type())) {
            // §9.4 reaches every `$type` too, and for the same reason: a look-alike type name is refused
            // rather than reported as naming nothing.
            if (!NameHygiene.refuses(ctx, tag.type())) {
                ctx.field(ReservedMembers.TYPE).report(Diagnostic.Code.TYPE_MISMATCH,
                        "'$type' names '%s', which is not admissible at a '%s' position -- a tag may name this "
                                .formatted(tag.type(), name) + "type or one of its subtypes ([TSON-SCHEMA] §7.2)",
                        admissible(), tag.type());
            }
            EventSkip.nextValue(ctx);
            return JsonNull.INSTANCE;
        }
        return tag.wrapper()
                ? ReservedMembers.readWrapped(ctx, readerFor.resolve(tag.type()))
                : inline(ctx, tag.type());
    }

    /**
     * §3.3's inline form: the reserved members stand beside the record's own, and the record is the object
     * minus them. A tag naming this entry reads here; one naming a subtype hands the whole object to that
     * type's reader, which scans it again, finds its own name, and reads it inline.
     */
    private JsonValue inline(JsonReadContext ctx, String type) {
        if (name.equals(type)) {
            return readObject(ctx);
        }
        return (JsonValue) readerFor.resolve(type).read(ctx);
    }

    /** What a {@code $type} may name here, for a diagnostic's machine-readable {@code expected}. */
    private String admissible() {
        return subtypes.isEmpty() ? name : name + " | " + String.join(" | ", subtypes);
    }

    /** The member loop, ending at the object's own close. Each member is matched, then read or discarded. */
    private void readMembers(JsonReadContext ctx, JsonValue[] values, boolean[] seen) {
        while (true) {
            JsonEvent event = ctx.next();
            if (event instanceof JsonEvent.ObjectEnd) {
                return;
            }
            if (!(event instanceof JsonEvent.MemberName member)) {
                throw new IllegalStateException("a member name or '}' was due and the stream produced " + event);
            }
            String memberName = Nfc.of(member.name());
            Integer at = index.get(memberName);
            if (at == null) {
                unmatched(ctx, memberName);
                continue;
            }
            if (seen[at]) {
                // §3.1: a repeated member name is an error at the repeated occurrence. The later value still
                // wins, so the record comes back whole and the diagnostic is what says the document was wrong.
                fieldContext(ctx, at).report(Diagnostic.Code.DUPLICATE_FIELD,
                        "duplicate member '%s' on '%s' -- a record states each field at most once, and the "
                                .formatted(memberName, name) + "repeat states a value for nothing",
                        "each member stated once", "'" + memberName + "' stated again");
            }
            seen[at] = true;
            values[at] = readField(ctx, at, memberName);
        }
    }

    /**
     * A member no declared field matches. §6.1.1's closure error, except that the reserved namespace is not
     * a field name at all: §3.2 reserves every {@code $}-initial name wherever member names are field names,
     * and the three it defines are the annotation object's, which §3.3 and §8 own and this reader does not
     * implement yet.
     */
    private void unmatched(JsonReadContext ctx, String memberName) {
        JsonReadContext at = ctx.field(memberName);
        if (ReservedMembers.isReserved(memberName)) {
            // §3.3: the record is the object minus its reserved members. Whether they were admissible here
            // was settled by the scan before any member was read, so passing over one now is not a decision
            // being skipped -- it is the decision already taken.
            EventSkip.nextValue(at);
            return;
        }
        // §8.2 before §6.1.1, and the order is the point: a name-hygiene refusal MUST NOT be reported in one
        // of §8.1's four categories, so a look-alike field name is refused here rather than told it is
        // unknown -- which would be a verdict on the document for a policy rule, and would advise adding a
        // field that is already declared.
        if (!NameHygiene.refuses(ctx, memberName)) {
            at.report(Diagnostic.Code.UNRECOGNIZED_FIELD, "unknown member '%s' on '%s' -- a record is closed "
                    .formatted(memberName, name) + "under its type (§7.2), whose fields are ("
                    + declaredFields + ")", declaredFields, memberName);
        }
        EventSkip.nextValue(at);
    }

    /**
     * One stated member, at its field. Null means the field is absent in decoded output -- §6.1.2's two
     * spellings of an absent optional field, which decoded output never distinguishes.
     */
    private JsonValue readField(JsonReadContext ctx, int at, String memberName) {
        RecordField field = fields.get(at);
        if (isFixed(field.state())) {
            return stated.get(at) != null
                    ? verifyFixed(ctx, at, memberName)
                    : fixedToAbsent(ctx, at, memberName);
        }
        if (ctx.peek() instanceof JsonEvent.NullValue) {
            return statedNull(ctx, at, memberName);
        }
        return (JsonValue) readers.get(at).read(fieldContext(ctx, at));
    }

    /**
     * {@code OPTIONAL_FIXED = _}: the group-member state, where the schema fixes the field to <em>absence</em>
     * and presence alone is the information ([TSON-SCHEMA] §5.2). So null is the member's only conforming
     * value, and it is kept -- omission remains its other, equivalent form.
     */
    private JsonValue fixedToAbsent(JsonReadContext ctx, int at, String memberName) {
        if (ctx.peek() instanceof JsonEvent.NullValue) {
            ctx.next();
            return JsonNull.INSTANCE;
        }
        JsonEvent event = ctx.next();
        fieldContext(ctx, at).report(Diagnostic.Code.FIELD_FIXED,
                "'%s' is fixed to absent on '%s' and may only be omitted or written null".formatted(memberName, name),
                "null", JsonAtoms.describe(event));
        EventSkip.value(ctx, event);
        return null;
    }

    /**
     * A member written null. §7 spends it as the absent sentinel before any type rule applies, so what
     * happens next is the field's <em>state</em> and nothing else.
     *
     * <p>The one state where the null spelling is the point is {@code OPTIONAL_FIXED = _} -- the group-member
     * state, where presence is the information (§6.1.2) -- so there the member is kept as null. At an
     * ordinary OPTIONAL field the two spellings are equivalent and decoded output records neither, so the
     * member is dropped: encoders SHOULD omit, and omission is therefore the canonical form to decode to.
     */
    private JsonValue statedNull(JsonReadContext ctx, int at, String memberName) {
        ctx.next();
        RecordField field = fields.get(at);
        return switch (field.state()) {
            case OPTIONAL -> null;
            case OPTIONAL_FIXED -> null;
            case REQUIRED, REQUIRED_FIXED -> {
                fieldContext(ctx, at).report(Diagnostic.Code.FIELD_REQUIRED,
                        "'%s' on '%s' admits no absence, and JSON null is this encoding's spelling of the absent "
                                .formatted(memberName, name) + "sentinel (§7)",
                        "a value for '" + memberName + "'", "null");
                yield null;
            }
            // §6.1.2: "at REQUIRED_DEFAULT the fix is omission, which injects the default". Injecting here
            // anyway would substitute a value the document explicitly disclaimed, so the default is still
            // what the field decodes to and only the verdict changes.
            case REQUIRED_DEFAULT -> {
                fieldContext(ctx, at).report(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                        "'%s' on '%s' is always filled from the schema and cannot be written null -- omit the "
                                .formatted(memberName, name) + "member to take its default (§6.1.3)",
                        "the member omitted, or a value for '" + memberName + "'", "null");
                yield stated.get(at).node();
            }
        };
    }

    /**
     * §6.1.3: a present member at a FIXED field MUST be verified against the pin, "a contradiction being a
     * validation error, never a silent overwrite". The schema's value is what the field decodes to either
     * way -- the document's member decides only whether the document is valid.
     *
     * <p><b>The comparison is over decoded values, not spellings.</b> §5.3 makes {@code 1} and {@code 1.0}
     * one value, so comparing the JSON literal against the schema's token would refuse a conforming document.
     *
     * <p><b>One wrong member yields one diagnostic.</b> If the member is not a value of the field's type at
     * all, the family's own parser has already said so; deriving a contradiction from a parse that failed
     * would report the same wrong member twice, the second time as evidence of nothing.
     */
    private JsonValue verifyFixed(JsonReadContext ctx, int at, String memberName) {
        RecordField field = fields.get(at);
        FieldValue pin = stated.get(at);
        JsonReadContext fieldCtx = fieldContext(ctx, at);
        JsonEvent event = ctx.peek();
        if (event instanceof JsonEvent.NullValue) {
            ctx.next();
            if (field.state() == FieldState.REQUIRED_FIXED) {
                fieldCtx.report(Diagnostic.Code.FIELD_FIXED,
                        "'%s' is fixed on '%s' and cannot be absent".formatted(memberName, name),
                        String.valueOf(pin.pinned()), "null");
                return null;
            }
            return null;   // OPTIONAL_FIXED: absence is exactly what it permits
        }
        String content = pin.form().contentOf(event);
        if (content == null) {
            // Not a value of the field's type at all. The reader below reports it; nothing is compared.
            return (JsonValue) readers.get(at).read(fieldCtx);
        }
        int before = ctx.reported();
        JsonValue written = (JsonValue) readers.get(at).read(fieldCtx);
        if (ctx.reported() > before) {
            return written;
        }
        Object value;
        try {
            value = pin.parser().read(content);
        } catch (RuntimeException ignored) {
            return written;   // already reported by the field's own reader
        }
        if (!Objects.equals(ValueIdentity.of(value), ValueIdentity.of(pin.pinned()))) {
            fieldCtx.report(Diagnostic.Code.FIELD_FIXED,
                    "'%s' is fixed on '%s' and cannot be given another value -- the schema declares it with '=' "
                            .formatted(memberName, name) + "(fixed); for a default the data may override, use '~'",
                    pin.text(), content);
        }
        // The schema's value, which is what an omitted FIXED member gets too: whether the document stated it
        // decides nothing about what the field holds (§6.1.3).
        return pin.node();
    }

    /**
     * Every field the document never mentioned: §6.1.3's injection, §6.1.2's permitted absences, and
     * §7.6's refusals, answered in one pass once the object has closed.
     */
    private void fillAbsent(JsonReadContext ctx, JsonValue[] values, boolean[] seen) {
        for (int i = 0; i < fields.size(); i++) {
            if (seen[i]) {
                continue;
            }
            RecordField field = fields.get(i);
            switch (field.state()) {
                case REQUIRED -> fieldContext(ctx, i).report(Diagnostic.Code.FIELD_REQUIRED,
                        "missing required member '%s' for '%s'".formatted(field.name(), name),
                        "a value for '" + field.name() + "'", "(absent)");
                // §6.1.3: a missing member at REQUIRED_DEFAULT or REQUIRED_FIXED injects, so decoded output
                // is fully populated. OPTIONAL and OPTIONAL_FIXED are never injected -- an omitted
                // OPTIONAL_FIXED field stays absent rather than materialising a value nobody wrote.
                case REQUIRED_DEFAULT, REQUIRED_FIXED -> values[i] = stated.get(i).node();
                case OPTIONAL, OPTIONAL_FIXED -> { }
            }
        }
    }

    /**
     * §6.1.4's field groups, counted after field validation. Grouping has no wire form -- members of a group
     * encode and decode as ordinary fields (§5.11) -- so this is the only place the group's own multiplicity
     * is enforced. A member written null still counts as present: presence is what a group counts.
     */
    private void validateGroups(JsonReadContext ctx, boolean[] seen) {
        for (FieldGroup group : groups) {
            int present = 0;
            for (String member : group.members()) {
                Integer at = index.get(Nfc.of(member));
                if (at != null && seen[at]) {
                    present++;
                }
            }
            String members = String.join(" | ", group.members());
            if (present > 1) {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                        "at most one of (%s) may be present for '%s', found %d".formatted(members, name, present),
                        "at most one of (" + members + ")", present + " present");
            } else if (group.state() == ElementState.REQUIRED && present == 0) {
                ctx.report(Diagnostic.Code.FIELD_REQUIRED,
                        "exactly one of (%s) must be present for '%s'".formatted(members, name),
                        "one of (" + members + ")", "none present");
            }
        }
    }

    private JsonReadContext fieldContext(JsonReadContext ctx, int at) {
        return ctx.schemaField(fields.get(at).name(), fields.get(at).position());
    }

    private static boolean isFixed(FieldState state) {
        return state == FieldState.REQUIRED_FIXED || state == FieldState.OPTIONAL_FIXED;
    }
}
