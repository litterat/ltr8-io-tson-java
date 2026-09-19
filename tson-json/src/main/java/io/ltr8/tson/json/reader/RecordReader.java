package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.unicode.Nfc;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.schema.meta.FieldState;

import java.util.Objects;

/**
 * A record as a JSON object, in every read mode: [TSON-JSON] §6.1. One member per present field, member name =
 * field name, member value read at the field's reader. Everything [TSON-SCHEMA] says about records binds here --
 * each field state, §6.1.3's injection and FIXED verification, §6.1.4's group count -- and nothing here depends
 * on what the read builds: the loop fills one slot per field and hands the slots to the mode's {@link
 * RecordBuilder} once per record.
 *
 * <p><b>A slot says what the document did with its field.</b> Null means the document did not state it; non-null
 * means it did, which is what the duplicate check, the group count and the absent-field pass all ask. The
 * {@link Slots} markers carry the cases a value cannot -- stated as absent, null kept at {@code OPTIONAL_FIXED = _},
 * and a child's refusal -- and each builder decides what they become.
 *
 * <p><b>Member order carries no meaning</b> (§6.1.6), so presence is settled once the object has closed: the
 * groups over what the document stated, then the fields it never mentioned -- refused, or injected with their
 * default or pin. The object's opening position is pinned for those, having no event of their own.
 *
 * <p><b>No rest field.</b> §6.2's {@code @rest} flatten is deliberately not implemented: a member matching no
 * declared field is §6.1.1's closure error, full stop.
 */
final class RecordReader implements JsonTypeReader<Object>, ExactReader {

    private final RecordPlan plan;

    /** The reader each field is read at, in this mode. */
    private final JsonTypeReader<?>[] readers;

    /** Each field's schema-stated value in this mode's form -- what an omitted default or pin injects. */
    private final Object[] injected;

    private final RecordBuilder builder;

    RecordReader(RecordPlan plan, JsonTypeReader<?>[] readers, Object[] injected, RecordBuilder builder) {
        this.plan = plan;
        this.readers = readers.clone();
        this.injected = injected.clone();
        this.builder = builder;
    }

    RecordBuilder builder() {
        return builder;
    }

    @Override
    public Object read(JsonReadContext ctx) {
        return readExact(ctx, this);
    }

    /**
     * The object itself -- §6.1's whole reading, and §3.3's annotation object where its first member is {@code
     * $type}. Nothing is read ahead: the leading members are judged as the loop meets them.
     */
    @Override
    public Object readExact(JsonReadContext ctx, JsonTypeReader<?> wrapped) {
        ctx = ctx.inRecord(plan.schemaLocation);
        JsonEvent opening = ctx.next();
        if (!(opening instanceof JsonEvent.ObjectStart)) {
            ctx.report(plan.rules.notARecord(JsonAtoms.describe(opening)));
            EventSkip.value(ctx, opening);
            return builder.refused();
        }
        int reportedBefore = ctx.reported();
        Object[] slots = new Object[plan.names.length];
        boolean tagged = false;
        for (int position = 0; ; position++) {
            JsonEvent event = ctx.next();
            if (event instanceof JsonEvent.ObjectEnd) {
                break;
            }
            if (!(event instanceof JsonEvent.MemberName member)) {
                throw new IllegalStateException("a member name or '}' was due and the stream produced " + event);
            }
            String name = member.name();
            if (ReservedMembers.isReserved(name)) {
                if (tagged && position == 1 && ReservedMembers.VALUE.equals(name)) {
                    // §3.3's wrapper: the value is `$value`, at the reader the enclosing position chose for it,
                    // since the value inside may carry a tag of its own.
                    Object value = ReservedMembers.readWrappedValue(ctx, wrapped);
                    return value == null ? builder.refused() : value;
                }
                if (position == 0 && ReservedMembers.TYPE.equals(name)) {
                    if (plan.admitsTag(ctx)) {
                        ctx.next();
                        tagged = true;
                        continue;
                    }
                } else {
                    plan.refuseReserved(ctx, name, tagged);
                }
                EventSkip.value(ctx, opening);
                return builder.refused();
            }
            String memberName = Nfc.of(name);
            int at = plan.slotOf(memberName);
            if (at < 0) {
                plan.unmatched(ctx, memberName);
                continue;
            }
            if (slots[at] != null) {
                plan.duplicate(ctx, at, memberName);
            }
            slots[at] = readField(ctx, at, memberName);
        }
        JsonReadContext closing = ctx.withPosition(opening.position());
        if (plan.hasGroups()) {
            plan.validateGroups(closing, slots);
        }
        fillAbsent(closing, slots);
        return builder.build(ctx, slots, ctx.reported() == reportedBefore);
    }

    /** One stated member, at its field: the slot it fills, never null, the member having been seen. */
    private Object readField(JsonReadContext ctx, int at, String memberName) {
        FieldState state = plan.states[at];
        if (state == FieldState.REQUIRED_FIXED || state == FieldState.OPTIONAL_FIXED) {
            return plan.stated[at] != null
                    ? verifyFixed(ctx, at, memberName)
                    : fixedToAbsent(ctx, at, memberName);
        }
        if (ctx.peek() instanceof JsonEvent.NullValue) {
            return statedNull(ctx, at, memberName);
        }
        return readValue(ctx, at);
    }

    /** A child's value for its slot: what it read, or {@link Slots#REFUSED} where it refused. */
    private Object readValue(JsonReadContext ctx, int at) {
        Object value = readers[at].read(plan.field(ctx, at));
        return value == null ? Slots.REFUSED : value;
    }

    /**
     * {@code OPTIONAL_FIXED = _}: the group-member state, where the schema fixes the field to <em>absence</em>
     * and presence alone is the information ([TSON-SCHEMA] §5.2). So null is the member's only conforming value,
     * and it is kept -- omission remains its other, equivalent form.
     */
    private Object fixedToAbsent(JsonReadContext ctx, int at, String memberName) {
        JsonEvent event = ctx.next();
        if (event instanceof JsonEvent.NullValue) {
            return Slots.NULL_KEPT;
        }
        plan.field(ctx, at).report(plan.rules.fixedToAbsentFieldValued(memberName, RecordPlan.NULL,
                JsonAtoms.describe(event)));
        EventSkip.value(ctx, event);
        return Slots.ABSENT;
    }

    /**
     * A member written null. §7 spends it as the absent sentinel before any type rule applies, so what happens
     * next is the field's <em>state</em> and nothing else: at an OPTIONAL field the two spellings are equivalent
     * and decoded output records neither, so the member decodes to absence.
     */
    private Object statedNull(JsonReadContext ctx, int at, String memberName) {
        ctx.next();
        return switch (plan.states[at]) {
            case OPTIONAL, OPTIONAL_FIXED -> Slots.ABSENT;
            case REQUIRED, REQUIRED_FIXED -> {
                plan.field(ctx, at).report(plan.rules.absenceAtRequiredField(memberName, RecordPlan.NULL));
                yield Slots.ABSENT;
            }
            // §6.1.2: "at REQUIRED_DEFAULT the fix is omission, which injects the default". Injecting here
            // anyway would substitute a value the document explicitly disclaimed, so the default is still what
            // the field decodes to and only the verdict changes.
            case REQUIRED_DEFAULT -> {
                plan.field(ctx, at).report(plan.rules.absenceAtDefaultedField(memberName, RecordPlan.NULL));
                yield injected[at];
            }
        };
    }

    /**
     * §6.1.3: a present member at a FIXED field MUST be verified against the pin, "a contradiction being a
     * validation error, never a silent overwrite". The schema's value is what the field decodes to either way --
     * the document's member decides only whether the document is valid.
     *
     * <p><b>The comparison is over decoded values, not spellings.</b> §5.3 makes {@code 1} and {@code 1.0} one
     * value, so comparing the JSON literal against the schema's token would refuse a conforming document.
     *
     * <p><b>One wrong member yields one diagnostic.</b> If the member is not a value of the field's type at all,
     * the family's own parser has already said so; deriving a contradiction from a parse that failed would
     * report the same wrong member twice, the second time as evidence of nothing.
     */
    private Object verifyFixed(JsonReadContext ctx, int at, String memberName) {
        FieldValue pin = plan.stated[at];
        JsonEvent event = ctx.peek();
        if (event instanceof JsonEvent.NullValue) {
            ctx.next();
            if (plan.states[at] == FieldState.REQUIRED_FIXED) {
                plan.field(ctx, at).report(plan.rules.fixedFieldAbsent(memberName, String.valueOf(pin.pinned()),
                        RecordPlan.NULL));
            }
            return Slots.ABSENT;   // OPTIONAL_FIXED: absence is exactly what it permits
        }
        String content = pin.form().contentOf(event);
        if (content == null) {
            // Not a value of the field's type at all. The reader reports it; nothing is compared.
            return readValue(ctx, at);
        }
        int before = ctx.reported();
        Object written = readValue(ctx, at);
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
            plan.field(ctx, at).report(plan.rules.fixedFieldContradicted(memberName, pin.text(), content));
        }
        // The schema's value, which is what an omitted FIXED member gets too: whether the document stated it
        // decides nothing about what the field holds (§6.1.3).
        return injected[at];
    }

    /**
     * Every field the document never mentioned: §6.1.3's injection, §6.1.2's permitted absences, and §7.6's
     * refusals. OPTIONAL and OPTIONAL_FIXED are never injected -- an omitted OPTIONAL_FIXED field stays absent
     * rather than materialising a value nobody wrote.
     */
    private void fillAbsent(JsonReadContext ctx, Object[] slots) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null) {
                continue;
            }
            switch (plan.states[i]) {
                case REQUIRED -> plan.field(ctx, i).report(plan.rules.missingRequiredField(plan.fields[i].name()));
                case REQUIRED_DEFAULT, REQUIRED_FIXED -> slots[i] = injected[i];
                case OPTIONAL, OPTIONAL_FIXED -> { }
            }
        }
    }
}
