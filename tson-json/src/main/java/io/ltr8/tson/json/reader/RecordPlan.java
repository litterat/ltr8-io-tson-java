package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.diagnostics.RecordDiagnostics;
import io.ltr8.tson.base.diagnostics.SubsumptionDiagnostics;
import io.ltr8.tson.base.unicode.Nfc;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a record's schema fixes, resolved once when the schema compiles, and the rules every mode reads a record
 * by: [TSON-JSON] §6.1's half that does not depend on what the read builds.
 *
 * <p>A mode's record factory builds one of these, then decides the two things that are its own -- the reader
 * each field is read at (a bound class re-targets an atom to its component) and the form a schema-stated value
 * takes -- and hands all three to {@link RecordReader}. The rules are methods here rather than a superclass, so
 * the loop reads as the whole of what it does and no mode words a rule of its own.
 */
final class RecordPlan {

    /** How a JSON document spells absence (§7), for the {@code actual} of a rule about a field's state. */
    static final String NULL = "null";

    final String name;
    final JsonSchemaLocation schemaLocation;

    /**
     * What this type is called in a message: the name the author wrote, where {@link #name} is the entry a
     * {@code $type} resolves against. The two differ for a minted entry, which shows as {@code box<text>} rather
     * than as a content-derived name in neither the schema nor the document.
     */
    final String displayName;

    /**
     * Every written name that means this entry: its own, and any alias whose chain ends at it. §7.2 compares
     * "after reference flattening of both", so a tag spelling an alias names this type and reads here.
     */
    private final Set<String> selfNames;

    /** Each field's name as a member name compares and is written -- NFC, computed once. */
    final String[] names;

    final RecordField[] fields;

    /** What each field yields when the document never writes it ({@link RecordField#omitted}). */
    final RecordField.Omitted[] omitted;

    /** The reader each field's declared type compiled to -- what a mode reads a field at unless it re-targets it. */
    final JsonTypeReader<?>[] schemaReaders;

    /** Each field's schema-stated value, null where it states none. */
    final FieldValue[] stated;

    private final Map<String, Integer> index;
    private final List<FieldGroup> groups;

    /** Each group's members as field slots; a member naming no field of this record is -1. */
    private final int[][] groupSlots;

    /** What this record's rules say when a document breaks one -- shared with the TSON reader ([TSON-JSON] §9.4). */
    final RecordDiagnostics rules;
    private final SubsumptionDiagnostics subsumption;

    RecordPlan(String name, TypeDefinition definition, ValueReaderContext context) {
        RecordBody body = (RecordBody) definition.body();
        this.name = name;
        this.schemaLocation = context.locationOf(name, definition);
        this.displayName = EntryDisplayName.of(name, definition, context.schema().entries());
        this.selfNames = Set.copyOf(context.admitting(List.of(name)));
        int count = body.fields().size();
        this.fields = body.fields().toArray(RecordField[]::new);
        this.names = new String[count];
        this.omitted = new RecordField.Omitted[count];
        this.schemaReaders = new JsonTypeReader<?>[count];
        this.stated = new FieldValue[count];
        Map<String, Integer> byName = new HashMap<>();
        for (int i = 0; i < count; i++) {
            RecordField field = fields[i];
            // §6.1.1: member names are NFC-normalized before matching, per [TSON-DATA] §7.2.1's resolver rule.
            names[i] = Nfc.of(field.name());
            byName.put(names[i], i);
            omitted[i] = field.omitted(body.groups().stream().anyMatch(group -> group.members().contains(field.name())));
            schemaReaders[i] = context.readers().resolve(field.type().name());
            if (field.value().isPresent()) {
                stated[i] = FieldValue.of(context.schema(), field.type().name(), field.value().get());
            }
        }
        this.index = Map.copyOf(byName);
        this.groups = List.copyOf(body.groups());
        this.groupSlots = groups.stream().map(group -> group.members().stream()
                .mapToInt(member -> byName.getOrDefault(Nfc.of(member), -1)).toArray()).toArray(int[][]::new);
        this.rules = new RecordDiagnostics(displayName, String.join(" | ", body.fields().stream()
                .map(RecordField::name).toList()));
        this.subsumption = new SubsumptionDiagnostics(displayName);
    }

    /** The field slot a member name fills, or -1 where it names no field. */
    int slotOf(String memberName) {
        Integer at = index.get(memberName);
        return at == null ? -1 : at;
    }

    /** {@code ctx} one declared field deeper, stepping the data path, the schema pointer and the schema line. */
    JsonReadContext field(JsonReadContext ctx, int at) {
        return ctx.schemaField(fields[at].name(), fields[at].position());
    }

    /** §3.1: a repeated member name is an error at the repeated occurrence; the later value still wins. */
    void duplicate(JsonReadContext ctx, int at, String memberName) {
        field(ctx, at).report(rules.duplicateField(memberName));
    }

    /**
     * Whether the {@code $type} at the cursor, leading its object, may stand here -- reporting it where it may
     * not. <b>A tag is never wrong</b> (§8.1): one restating this entry, or an alias of it, is admitted and
     * changes nothing. It may not name another type -- a record is read here only for its own, a tag naming a
     * subtype having been placed by the dispatcher in front of a record that has any ({@link DispatchFactories}).
     */
    boolean admitsTag(JsonReadContext ctx) {
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
     * A reserved member where none may stand (§3.2, §3.3): a {@code $schema}, which no record position admits;
     * a {@code $type} or {@code $value} out of its leading place; or a name outside the closed set.
     */
    void refuseReserved(JsonReadContext ctx, String member, boolean tagged) {
        switch (member) {
            case ReservedMembers.SCHEMA -> Tags.refuseScope(ctx, displayName, Tags.RECORD);
            case ReservedMembers.TYPE -> ReservedMembers.refuseMisplaced(ctx, member);
            case ReservedMembers.VALUE -> ctx.field(member).report(Diagnostic.Code.UNRECOGNIZED_FIELD, tagged
                    ? "'$value' follows members of the record's own, and an annotation object in wrapper form "
                            + "admits nothing beside it (§3.3)"
                    : "'$value' belongs to an annotation object in wrapper form, which leads with '$type' "
                            + "naming the value's type (§3.3)", "'$value' straight after a leading '$type'", member);
            default -> ReservedMembers.refuseUnknown(ctx, member);
        }
    }

    /**
     * A member no declared field matches: §6.1.1's closure error. §8.2 before §6.1.1, and the order is the
     * point: a name-hygiene refusal MUST NOT be reported in one of §8.1's four categories, so a look-alike field
     * name is refused here rather than told it is unknown -- which would be a verdict on the document for a
     * policy rule, and would advise adding a field that is already declared.
     */
    void unmatched(JsonReadContext ctx, String memberName) {
        JsonReadContext at = ctx.field(memberName);
        if (!NameHygiene.refuses(ctx, memberName)) {
            at.report(rules.unrecognizedField(memberName));
        }
        EventSkip.nextValue(at);
    }

    /**
     * §6.1.4's field groups, counted over what the document stated -- a non-null slot, before anything is
     * injected. Grouping has no wire form (§5.11), so this is the only place a group's multiplicity is enforced,
     * and a member written null still counts: presence is what a group counts.
     */
    void validateGroups(JsonReadContext ctx, Object[] slots) {
        for (int g = 0; g < groupSlots.length; g++) {
            int present = 0;
            for (int at : groupSlots[g]) {
                if (at >= 0 && slots[at] != null) {
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

    boolean hasGroups() {
        return groupSlots.length > 0;
    }
}
