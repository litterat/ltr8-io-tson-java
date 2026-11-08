package io.ltr8.tson.json.reader;

import io.ltr8.unicode.Nfc;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.RecordField;

import java.util.Objects;

/**
 * A record as a JSON object, in every read mode: [TSON-JSON] §6.1. One member per present field, member name =
 * field name, member value read at the field's reader. Everything [TSON-SCHEMA] says about records binds here --
 * each field state, §6.1.3's injection and FIXED verification, §6.1.4's group count -- and nothing here depends
 * on what the read builds: the loop fills one slot per field and hands the slots to the mode's {@link
 * RecordBuilder} once per record.
 *
 * <p><b>A slot says what the document did with its field.</b> Null means the document did not state it; non-null
 * means it did, which is what the duplicate check, the group count and the missing-field pass all ask. The
 * {@link Slots} markers carry the cases a value cannot -- stated void, and a child's refusal -- and each
 * builder decides what they become.
 *
 * <p><b>Member order carries no meaning</b> (§6.1.6), so presence is settled once the object has closed: the
 * groups over what the document stated, then the fields it never mentioned -- refused, or injected with their
 * default or pin. The object's opening position is pinned for those, having no event of their own. The one exception
 * is a dependent field's free sibling ([TSON-SCHEMA] §5.2): a sibling neither pinned nor a discriminator is written
 * before the dependent member, or left out with its default applying, so a member is never held before it can be
 * typed; written after the member, it is the same refusal, found when it arrives. A sibling that never arrives is its
 * own refusal: a required one the missing-field error, and a field group's member, absent where another option is
 * chosen, reported at the member once the object closes. A pinned sibling's pin types the member wherever the sibling
 * stands, and a discriminator's member dispatch has selected a member that pins it.
 *
 * <p><b>Closure is the whole answer.</b> A record absorbs nothing: a member matching no declared field is
 * §6.1.1's closure error, and open-ended data is carried by a declared map-typed field instead.
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
        // Kept only where a repeat may be another spelling, so an ordinary read allocates none (RecordPlan.duplicate).
        JsonEvent.MemberName[] firstGiven = plan.folds() ? new JsonEvent.MemberName[slots.length] : null;
        boolean tagged = false;
        // A dependent field's state, kept only where the record has one: each sibling's reference as written, and
        // each dependent member reached before its sibling was written, by the name written and where it stands.
        io.ltr8.tson.schema.meta.Reference[] named = plan.hasDependents
                ? new io.ltr8.tson.schema.meta.Reference[slots.length] : null;
        String[] readBefore = plan.hasDependents ? new String[slots.length] : null;
        JsonReadContext[] readBeforeAt = plan.hasDependents ? new JsonReadContext[slots.length] : null;
        for (int position = 0; ; position++) {
            JsonEvent event = ctx.next();
            if (event instanceof JsonEvent.ObjectEnd) {
                break;
            }
            if (!(event instanceof JsonEvent.MemberName member)) {
                throw new IllegalStateException("a member name or '}' was due and the stream produced " + event);
            }
            String name = member.name();
            if (TagMembers.isTagMember(name)) {
                if (tagged && position == 1 && TagMembers.VALUE.equals(name)) {
                    // §3.3's wrapper: the value is `$value`, at the reader the enclosing position chose for it,
                    // since the value inside may carry a tag of its own.
                    Object value = TagMembers.readWrappedValue(ctx, wrapped);
                    return value == null ? builder.refused() : value;
                }
                if (position == 0 && TagMembers.TYPE.equals(name)) {
                    if (plan.admitsTag(ctx)) {
                        ctx.next();
                        tagged = true;
                        continue;
                    }
                } else {
                    plan.refuseTagMember(ctx, name, tagged);
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
                plan.duplicate(ctx, at, memberName, firstGiven == null ? null : firstGiven[at]);
            } else if (firstGiven != null) {
                firstGiven[at] = member;
            }
            if (named == null) {
                slots[at] = readField(ctx, at, memberName);
                continue;
            }
            if (plan.typesADependent[at]) {
                siblingArrives(at, readBefore, readBeforeAt);
                String text = ctx.peek() instanceof JsonEvent.StringValue string ? string.value() : null;
                int before = ctx.reported();
                slots[at] = readField(ctx, at, memberName);
                named[at] = ctx.reported() > before ? null : DependentReader.referenceIn(text);
            } else if (plan.siblingOf[at] >= 0 && !(ctx.peek() instanceof JsonEvent.NullValue)) {
                slots[at] = readDependent(ctx, at, memberName, slots, named, readBefore, readBeforeAt);
            } else {
                slots[at] = readField(ctx, at, memberName);
            }
        }
        if (plan.hasDependents) {
            refuseAbsentSiblings(slots, readBefore, readBeforeAt);
        }
        JsonReadContext closing = ctx.withPosition(opening.position());
        if (plan.hasGroups()) {
            plan.validateGroups(closing, slots);
        }
        fillMissing(closing, slots);
        return builder.build(ctx, slots, ctx.reported() == reportedBefore);
    }

    /**
     * A dependent member, read against the type its sibling names in this object: the pin, the sibling as written
     * before it, or the sibling's default where it is not yet written. A free sibling with no default that is not yet
     * written leaves the member with no type: it is skipped unjudged, and refused when the sibling arrives, the order
     * rule, or when the object closes without it ([TSON-SCHEMA] §5.2).
     */
    private Object readDependent(JsonReadContext ctx, int at, String memberName, Object[] slots,
                                 io.ltr8.tson.schema.meta.Reference[] named, String[] readBefore,
                                 JsonReadContext[] readBeforeAt) {
        int sibling = plan.siblingOf[at];
        JsonReadContext fieldCtx = plan.field(ctx, at, memberName);
        io.ltr8.tson.schema.meta.Reference reference;
        if (plan.fields[sibling].role() == FieldRole.FIXED && plan.stated[sibling] != null) {
            reference = DependentReader.referenceIn(plan.stated[sibling].text());
        } else if (slots[sibling] != null) {
            reference = named[sibling];
        } else if (plan.omitted[sibling] == RecordField.Omitted.VALUE && plan.stated[sibling] != null) {
            reference = DependentReader.referenceIn(plan.stated[sibling].text());
            readBefore[at] = memberName;
            readBeforeAt[at] = fieldCtx.withPosition(fieldCtx.peek().position());
        } else {
            readBefore[at] = memberName;
            readBeforeAt[at] = fieldCtx.withPosition(fieldCtx.peek().position());
            EventSkip.nextValue(fieldCtx);
            return Slots.REFUSED;
        }
        Object value = ((DependentReader) readers[at]).readAgainst(fieldCtx, reference, plan.field(ctx, sibling),
                plan.rules);
        return value == null ? Slots.REFUSED : value;
    }

    /**
     * Sibling {@code at} is written: a free one after a dependent member reached before it is the order rule broken,
     * reported at that member. A pinned sibling orders nothing.
     */
    private void siblingArrives(int at, String[] readBefore, JsonReadContext[] readBeforeAt) {
        if (plan.fields[at].role() == FieldRole.FIXED) {
            return;
        }
        for (int d = 0; d < readBefore.length; d++) {
            if (readBefore[d] != null && plan.siblingOf[d] == at) {
                readBeforeAt[d].report(plan.rules.writtenBeforeItsSibling(readBefore[d], plan.declaredNames[at]));
                readBefore[d] = null;
            }
        }
    }

    /**
     * The object has closed: each dependent member still waiting on a sibling that never arrived, a field group's
     * member, is refused where it was written, nothing having typed it. A sibling with a default typed the member, and
     * a required one is the missing-field error.
     */
    private void refuseAbsentSiblings(Object[] slots, String[] readBefore, JsonReadContext[] readBeforeAt) {
        for (int d = 0; d < readBefore.length; d++) {
            if (readBefore[d] != null && slots[plan.siblingOf[d]] == null
                    && plan.omitted[plan.siblingOf[d]] == RecordField.Omitted.NOTHING) {
                readBeforeAt[d].report(plan.rules.writtenWithoutItsSibling(readBefore[d],
                        plan.declaredNames[plan.siblingOf[d]]));
            }
        }
    }

    /** One stated member, at its field: the slot it fills, never null, the member having been seen. */
    private Object readField(JsonReadContext ctx, int at, String memberName) {
        if (plan.fields[at].role() == FieldRole.FIXED) {
            return verifyFixed(ctx, at, memberName);
        }
        if (ctx.peek() instanceof JsonEvent.NullValue) {
            return statedNull(ctx, at, memberName);
        }
        return readValue(ctx, at, memberName);
    }

    /** A child's value for its slot: what it read, or {@link Slots#REFUSED} where it refused. */
    private Object readValue(JsonReadContext ctx, int at, String memberName) {
        Object value = readers[at].read(plan.field(ctx, at, memberName));
        return value == null ? Slots.REFUSED : value;
    }

    /**
     * A member written null. §7 spends it as the void sentinel before any type rule applies, so what happens
     * next is the field's facts and nothing else: at a voidable field the member decodes to a void value, stated --
     * which a tree keeps as {@code JsonNull} and bind mode delivers as {@code null} (§7.2). A FIXED field never
     * reaches here.
     */
    private Object statedNull(JsonReadContext ctx, int at, String memberName) {
        ctx.next();
        RecordField field = plan.fields[at];
        if (field.voidable()) {
            return Slots.VOID;
        }
        if (field.role() == FieldRole.DEFAULT) {
            // §6.1.2: "at REQUIRED_DEFAULT the fix is omission, which injects the default". Injecting here
            // anyway would substitute a value the document explicitly disclaimed, so the default is still what
            // the field decodes to and only the verdict changes.
            plan.field(ctx, at, memberName).report(plan.rules.voidAtDefaultedField(memberName, RecordPlan.NULL));
            return injected[at];
        }
        plan.field(ctx, at, memberName).report(plan.rules.voidAtRequiredField(memberName, RecordPlan.NULL));
        return Slots.VOID;
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
            // A field pinned to a value is never voidable: `_` is not the pin.
            plan.field(ctx, at, memberName).report(plan.rules.voidAtFixedField(memberName, String.valueOf(pin.pinned()),
                    RecordPlan.NULL));
            return Slots.VOID;
        }
        String content = pin.form().contentOf(event);
        if (content == null) {
            // Not a value of the field's type at all. The reader reports it; nothing is compared.
            return readValue(ctx, at, memberName);
        }
        int before = ctx.reported();
        Object written = readValue(ctx, at, memberName);
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
            plan.field(ctx, at, memberName).report(plan.rules.fixedFieldContradicted(memberName,
                    Nodes.rendered(pin.pinned()), content));
        }
        // The schema's value, which is what an omitted FIXED member gets too: whether the document stated it
        // decides nothing about what the field holds (§6.1.3).
        return injected[at];
    }

    /**
     * Every field the document never mentioned: §6.1.3's injection, §6.1.2's permitted omissions, and §7.6's
     * refusals, as {@link RecordField#omitted} derives them.
     */
    private void fillMissing(JsonReadContext ctx, Object[] slots) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null) {
                continue;
            }
            switch (plan.omitted[i]) {
                case MISSING -> plan.field(ctx, i).report(plan.rules.missingRequiredField(plan.declaredNames[i]));
                case VALUE -> slots[i] = injected[i];
                case NOTHING -> { }
            }
        }
    }
}
