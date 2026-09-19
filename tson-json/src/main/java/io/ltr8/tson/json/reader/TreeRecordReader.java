package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.diagnostics.RecordDiagnostics;
import io.ltr8.tson.base.diagnostics.SubsumptionDiagnostics;
import io.ltr8.tson.base.unicode.Nfc;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonObject;
import io.ltr8.tson.json.tree.JsonValue;
import io.ltr8.tson.schema.meta.EntryDisplayName;
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
final class TreeRecordReader implements JsonTypeReader<JsonValue>, ExactReader {

    /**
     * The concrete reading of an OPEN or FINAL record. Which reader a record position actually gets is {@link
     * DispatchFactories}'s decision, taken over this factory: a record with subtypes, and every ABSTRACT and
     * SEALED base, is a dispatcher that places the value and hands the object here only once it is known to
     * be this record's own.
     */
    static final ValueReaderFactory FACTORY = (name, definition, context) -> new TreeRecordReader(name,
            context.admitting(List.of(name)), EntryDisplayName.of(name, definition, context.schema().entries()),
            (RecordBody) definition.body(), context, context.locationOf(name, definition));

    private final String name;

    /**
     * Every written name that means this entry: its own, and any alias whose chain ends at it. §7.2 compares
     * "after reference flattening of both", so a tag spelling an alias names this type and reads here.
     */
    private final Set<String> selfNames;

    private final List<RecordField> fields;

    /** Each field's name as a member name compares and is written -- NFC, computed once rather than per record. */
    private final String[] names;

    private final Map<String, Integer> index;
    private final List<JsonTypeReader<?>> readers;
    private final List<FieldValue> stated;
    private final List<FieldGroup> groups;

    /** Each group's members as field slots, resolved once; a member naming no field of this record is -1. */
    private final int[][] groupSlots;

    /**
     * Whether this record has any field group at all, decided when the schema compiles. §5.11's groups are
     * the exception rather than the rule, and the pass they need walks every member of every group and
     * indexes each -- work a record without one should not carry to the position where it is skipped.
     */
    private final boolean hasGroups;

    private final String declaredFields;

    /** What this record's rules say when a document breaks one -- shared with the TSON reader ([TSON-JSON] §9.4). */
    private final RecordDiagnostics rules;
    private final SubsumptionDiagnostics subsumption;
    private final JsonSchemaLocation schemaLocation;

    /**
     * What this type is called in a message: the name the author wrote, where {@link #name} is the entry a
     * {@code $type} resolves against. The two differ for an entry the resolver minted -- a record template's
     * instantiation shows as {@code box<text>} rather than by a content-derived name that appears in neither
     * the author's schema nor the sender's document.
     */
    private final String displayName;

    private TreeRecordReader(String name, Collection<String> selfNames, String displayName, RecordBody body,
                             ValueReaderContext context, JsonSchemaLocation schemaLocation) {
        this.displayName = displayName;
        this.name = name;
        this.selfNames = Set.copyOf(selfNames);
        this.fields = List.copyOf(body.fields());
        this.groups = List.copyOf(body.groups());
        this.hasGroups = !this.groups.isEmpty();
        this.schemaLocation = schemaLocation;
        Map<String, Integer> byName = new LinkedHashMap<>();
        this.names = new String[fields.size()];
        List<JsonTypeReader<?>> built = new ArrayList<>(fields.size());
        List<FieldValue> values = new ArrayList<>(fields.size());
        for (int i = 0; i < fields.size(); i++) {
            RecordField field = fields.get(i);
            // §6.1.1: member names are NFC-normalized before matching, per [TSON-DATA] §7.2.1's resolver rule.
            names[i] = Nfc.of(field.name());
            byName.put(names[i], i);
            built.add(context.readers().resolve(field.type().name()));
            values.add(field.value()
                    .map(token -> FieldValue.of(context.schema(), field.type().name(), token))
                    .orElse(null));
        }
        this.index = Map.copyOf(byName);
        this.groupSlots = groups.stream().map(group -> group.members().stream()
                .mapToInt(member -> byName.getOrDefault(Nfc.of(member), -1)).toArray()).toArray(int[][]::new);
        this.readers = List.copyOf(built);
        this.stated = new ArrayList<>(values);
        this.declaredFields = fields.stream().map(RecordField::name).reduce((a, b) -> a + " | " + b).orElse("");
        this.rules = new RecordDiagnostics(displayName, declaredFields);
        this.subsumption = new SubsumptionDiagnostics(displayName);
    }

    @Override
    public JsonValue read(JsonReadContext ctx) {
        return readExact(ctx, this);
    }

    /**
     * The object itself -- §6.1's whole reading, and §3.3's annotation object where its first member is {@code
     * $type}. Nothing is read ahead: the leading members are judged as the member loop meets them.
     */
    @Override
    public JsonValue readExact(JsonReadContext ctx, JsonTypeReader<?> wrapped) {
        ctx = ctx.inRecord(schemaLocation);
        JsonEvent first = ctx.next();
        if (!(first instanceof JsonEvent.ObjectStart)) {
            ctx.report(rules.notARecord(JsonAtoms.describe(first)));
            EventSkip.value(ctx, first);
            return JsonNull.INSTANCE;
        }
        JsonValue[] values = new JsonValue[fields.size()];
        boolean[] seen = new boolean[fields.size()];
        JsonValue instead = readMembers(ctx, first, values, seen, wrapped);
        if (instead != null) {
            return instead;
        }
        fillAbsent(ctx.withPosition(first.position()), values, seen);
        if (hasGroups) {
            validateGroups(ctx.withPosition(first.position()), seen);
        }
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
        JsonObject.Builder members = JsonObject.builder(values.length);
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                members.put(names[i], values[i]);
            }
        }
        return members.build();
    }

    /**
     * The member loop, ending at the object's own close. Each member is matched, then read or discarded.
     *
     * <p>Answers null when the record was read, and otherwise what the position yields instead: the wrapper's
     * value, or tree mode's placeholder once a reserved member has refused the object -- the rest of it then
     * skipped, the refusal being the object's.
     */
    private JsonValue readMembers(JsonReadContext ctx, JsonEvent opening, JsonValue[] values, boolean[] seen,
                                  JsonTypeReader<?> wrapped) {
        boolean tagged = false;
        for (int position = 0; ; position++) {
            JsonEvent event = ctx.next();
            if (event instanceof JsonEvent.ObjectEnd) {
                return null;
            }
            if (!(event instanceof JsonEvent.MemberName member)) {
                throw new IllegalStateException("a member name or '}' was due and the stream produced " + event);
            }
            if (ReservedMembers.isReserved(member.name())) {
                String name = member.name();
                if (ReservedMembers.VALUE.equals(name) && tagged && position == 1) {
                    // §3.3's wrapper: the value is `$value`, at the reader the enclosing position chose for it,
                    // since the value inside may carry a tag of its own.
                    return Nodes.node(ReservedMembers.readWrappedValue(ctx, wrapped));
                }
                if (ReservedMembers.TYPE.equals(name) && position == 0 && admitsTag(ctx)) {
                    tagged = true;
                    ctx.next();
                    continue;
                }
                if (!ReservedMembers.TYPE.equals(name) || position != 0) {
                    refuseReserved(ctx, name, tagged);
                }
                EventSkip.value(ctx, opening);
                return JsonNull.INSTANCE;
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
                fieldContext(ctx, at).report(rules.duplicateField(memberName));
            }
            seen[at] = true;
            values[at] = readField(ctx, at, memberName);
        }
    }

    /**
     * Whether the leading {@code $type} at the cursor may stand here, reporting it where it may not. <b>A tag is
     * never wrong</b> (§8.1): one restating this entry, or an alias of it, is admitted and changes nothing. It
     * may not name another type -- this reader is reached only for its own, a tag naming a subtype having been
     * placed by the dispatcher in front of a record that has any ({@link DispatchFactories}).
     */
    private boolean admitsTag(JsonReadContext ctx) {
        if (!(ctx.peek() instanceof JsonEvent.StringValue tag)) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                    "this object leads with this encoding's reserved members but no '$type' naming a type (§3.3)",
                    "a '$type' member holding a type name", "no $type");
            return false;
        }
        if (selfNames.contains(tag.value())) {
            return true;
        }
        // §9.4 reaches every `$type`, and a look-alike type name is refused rather than reported as naming
        // nothing. At the value, not at `/$type`, though the member is right there: §9.4 holds both encodings
        // to one pointer for a rule they share, and TSON's tag is an annotation with no pointer step of its own.
        if (!NameHygiene.refuses(ctx, tag.value())) {
            ctx.report(subsumption.noSubtypeToName(tag.value()));
        }
        return false;
    }

    /**
     * A reserved member where none may stand (§3.2, §3.3): a {@code $schema}, which no record position
     * admits; a {@code $type} or {@code $value} out of its leading place; or a name outside the closed set.
     */
    private void refuseReserved(JsonReadContext ctx, String name, boolean tagged) {
        switch (name) {
            case ReservedMembers.SCHEMA -> Tags.refuseScope(ctx, displayName, Tags.RECORD);
            case ReservedMembers.TYPE -> ReservedMembers.refuseMisplaced(ctx, name);
            case ReservedMembers.VALUE -> ctx.field(name).report(Diagnostic.Code.UNRECOGNIZED_FIELD, tagged
                    ? "'$value' follows members of the record's own, and an annotation object in wrapper form "
                            + "admits nothing beside it (§3.3)"
                    : "'$value' belongs to an annotation object in wrapper form, which leads with '$type' "
                            + "naming the value's type (§3.3)", "'$value' straight after a leading '$type'", name);
            default -> ReservedMembers.refuseUnknown(ctx, name);
        }
    }

    /**
     * A member no declared field matches: §6.1.1's closure error. A reserved name never reaches here -- §3.2
     * reserves every {@code $}-initial name wherever member names are field names, and the loop judges those.
     */
    private void unmatched(JsonReadContext ctx, String memberName) {
        JsonReadContext at = ctx.field(memberName);
        // §8.2 before §6.1.1, and the order is the point: a name-hygiene refusal MUST NOT be reported in one
        // of §8.1's four categories, so a look-alike field name is refused here rather than told it is
        // unknown -- which would be a verdict on the document for a policy rule, and would advise adding a
        // field that is already declared.
        if (!NameHygiene.refuses(ctx, memberName)) {
            at.report(rules.unrecognizedField(memberName));
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
        return Nodes.node(readers.get(at).read(fieldContext(ctx, at)));
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
        fieldContext(ctx, at).report(rules.fixedToAbsentFieldValued(memberName, ABSENT,
                JsonAtoms.describe(event)));
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
                fieldContext(ctx, at).report(rules.absenceAtRequiredField(memberName, ABSENT));
                yield null;
            }
            // §6.1.2: "at REQUIRED_DEFAULT the fix is omission, which injects the default". Injecting here
            // anyway would substitute a value the document explicitly disclaimed, so the default is still
            // what the field decodes to and only the verdict changes.
            case REQUIRED_DEFAULT -> {
                fieldContext(ctx, at).report(rules.absenceAtDefaultedField(memberName, ABSENT));
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
                fieldCtx.report(rules.fixedFieldAbsent(memberName, String.valueOf(pin.pinned()), ABSENT));
                return null;
            }
            return null;   // OPTIONAL_FIXED: absence is exactly what it permits
        }
        String content = pin.form().contentOf(event);
        if (content == null) {
            // Not a value of the field's type at all. The reader below reports it; nothing is compared.
            return Nodes.node(readers.get(at).read(fieldCtx));
        }
        int before = ctx.reported();
        JsonValue written = Nodes.node(readers.get(at).read(fieldCtx));
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
            fieldCtx.report(rules.fixedFieldContradicted(memberName, pin.text(), content));
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
                case REQUIRED -> fieldContext(ctx, i).report(rules.missingRequiredField(field.name()));
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
        for (int g = 0; g < groupSlots.length; g++) {
            int present = 0;
            for (int at : groupSlots[g]) {
                if (at >= 0 && seen[at]) {
                    present++;
                }
            }
            FieldGroup group = groups.get(g);
            if (present > 1) {
                ctx.report(rules.groupAdmitsAtMostOne(String.join(" | ", group.members()), present));
            } else if (group.state() == ElementState.REQUIRED && present == 0) {
                ctx.report(rules.groupRequiresOne(String.join(" | ", group.members())));
            }
        }
    }

    private JsonReadContext fieldContext(JsonReadContext ctx, int at) {
        return ctx.schemaField(fields.get(at).name(), fields.get(at).position());
    }

    /** How a JSON document spells absence (§7), for the `actual` of a rule about a field's state. */
    private static final String ABSENT = "null";

    private static boolean isFixed(FieldState state) {
        return state == FieldState.REQUIRED_FIXED || state == FieldState.OPTIONAL_FIXED;
    }
}
