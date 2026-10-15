package io.ltr8.tson.json.reader;

import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.atom.IdentifierGrammar;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.diagnostics.RecordDiagnostics;
import io.ltr8.tson.base.diagnostics.SubsumptionDiagnostics;
import io.ltr8.unicode.IdentifierProfile;
import io.ltr8.unicode.Nfc;
import io.ltr8.unicode.Normalization;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.IdentifierType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Top;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** How a JSON document spells the void sentinel (§7), for the {@code actual} of a rule about a voidable field. */
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

    /** Each field's name as the record declares it -- what a schema pointer and a message name it by. */
    final String[] declaredNames;

    final RecordField[] fields;

    /** What each field yields when the document never writes it ({@link RecordField#omitted}). */
    final RecordField.Omitted[] omitted;

    /** The reader each field's declared type compiled to -- what a mode reads a field at unless it re-targets it. */
    final JsonTypeReader<?>[] schemaReaders;

    /** Each field's schema-stated value, null where it states none. */
    final FieldValue[] stated;

    private final Map<String, Integer> index;
    private final Normalization nameForm;

    /**
     * The record's name type, by name for messages, and its reader -- {@code null} for the kernel's
     * {@code field_name}, which §7.7's grammar answers without allocating -- and the profile a member name it
     * admits is judged under ({@link #unmatched}).
     */
    private final String nameType;
    private final AtomType<?> nameReader;
    private final IdentifierProfile nameProfile;

    /** Each field group, compiled to slots once ({@link GroupPlan}). */
    private final GroupPlan[] groups;

    /** What this record's rules say when a document breaks one -- shared with the TSON reader ([TSON-JSON] §9.4). */
    final RecordDiagnostics rules;
    private final SubsumptionDiagnostics subsumption;

    RecordPlan(String name, Top definition, ValueReaderContext context) {
        RecordBody body = (RecordBody) definition;
        this.name = name;
        this.schemaLocation = context.locationOf(name);
        this.displayName = EntryDisplayName.of(name, context.schema());
        this.selfNames = Set.copyOf(context.admitting(List.of(name)));
        int count = body.fields().size();
        this.declaredNames = body.fields().keySet().toArray(String[]::new);
        this.fields = body.fields().values().toArray(RecordField[]::new);
        this.names = new String[count];
        this.omitted = new RecordField.Omitted[count];
        this.schemaReaders = new JsonTypeReader<?>[count];
        this.stated = new FieldValue[count];
        Map<String, Integer> byName = new HashMap<>();
        // §6.1.1: member names are NFC-normalized before matching, per [TSON-DATA] §7.2.1's resolver rule, and
        // then put into the record's name type's form (§5.5), so a name the type holds equal to a field is it.
        this.nameForm = context.linked().nameForm(name);
        this.nameType = body.nameTypeName();
        IdentifierType identifiers = context.linked().nameType(name)
                .map(type -> type instanceof IdentifierType family ? family : null)
                .orElse(null);
        this.nameReader = identifiers == null ? null : AtomParsers.forType(identifiers).orElseThrow(() ->
                new IllegalStateException("'" + name + "': name_type '" + nameType + "' has no reader of its own"));
        this.nameProfile = identifiers == null ? IdentifierGrammar.PROFILE : identifiers.profile();
        for (int i = 0; i < count; i++) {
            RecordField field = fields[i];
            String declared = declaredNames[i];
            names[i] = Nfc.of(declared);
            byName.put(nameForm.apply(names[i]), i);
            omitted[i] = field.omitted(body.groups().stream().anyMatch(group -> group.hasMember(declared)));
            schemaReaders[i] = context.readers().resolve(field.type().name());
            if (field.value().isPresent()) {
                stated[i] = FieldValue.of(context.linked(), field.type().name(), field.value().get());
            }
        }
        this.index = Map.copyOf(byName);
        this.groups = body.groups().stream().map(group -> GroupPlan.of(group, byName, nameForm))
                .toArray(GroupPlan[]::new);
        this.rules = new RecordDiagnostics(displayName, String.join(" | ", body.fields().keySet()));
        this.subsumption = new SubsumptionDiagnostics(displayName);
    }

    /** The field slot an NFC member name fills, in the name type's form, or -1 where it names no field. */
    int slotOf(String memberName) {
        Integer at = index.get(nameForm.apply(memberName));
        return at == null ? -1 : at;
    }

    /**
     * {@code ctx} one declared field deeper, stepping the data path by {@code memberName}, the member as the
     * document wrote it (NFC), and the schema pointer and line by the field -- the two differ where the name type
     * folds case, and only the written name is a pointer into the document.
     */
    JsonReadContext field(JsonReadContext ctx, int at, String memberName) {
        return ctx.schemaField(memberName, declaredNames[at], fields[at].position());
    }

    /** {@link #field(JsonReadContext, int, String)} for a field the document never wrote, which has one name. */
    JsonReadContext field(JsonReadContext ctx, int at) {
        return field(ctx, at, declaredNames[at]);
    }

    /**
     * §3.1: a repeated member name is an error at the repeated occurrence; the later value still wins. Under a
     * folding name type {@code first} is the member that first gave the field, which the report names with its
     * position, a repeat there possibly being another spelling; {@code null} under NFC, where the two are one.
     */
    void duplicate(JsonReadContext ctx, int at, String memberName, JsonEvent.MemberName first) {
        field(ctx, at, memberName).report(first == null ? rules.duplicateField(memberName)
                : rules.duplicateField(memberName, declaredNames[at], Nfc.of(first.name()), first.position()));
    }

    /** Whether this record's name type folds, so a repeat may be another spelling ({@link #duplicate}). */
    boolean folds() {
        return nameForm != Normalization.NFC;
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
                    "this object leads with this encoding's annotation members but no '$type' naming a type (§3.3)",
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
     * One of §3.2's three where none may stand (§3.3): a {@code $schema}, which no record position admits, or a
     * {@code $type} or {@code $value} out of its leading place.
     */
    void refuseTagMember(JsonReadContext ctx, String member, boolean tagged) {
        switch (member) {
            case TagMembers.SCHEMA -> Tags.refuseScope(ctx, displayName, Tags.RECORD);
            case TagMembers.TYPE -> TagMembers.refuseMisplaced(ctx, member);
            case TagMembers.VALUE -> ctx.field(member).report(Diagnostic.Code.UNRECOGNIZED_FIELD, tagged
                    ? "'$value' follows members of the record's own, and an annotation object in wrapper form "
                            + "admits nothing beside it (§3.3)"
                    : "'$value' belongs to an annotation object in wrapper form, which leads with '$type' "
                            + "naming the value's type (§3.3)", "'$value' straight after a leading '$type'", member);
            default -> throw new IllegalStateException("'" + member + "' is none of §3.2's three");
        }
    }

    /**
     * A member no declared field matches: §6.1.1's closure error, judged by the record's name type
     * (SPEC-FEEDBACK.md #10). A name that is no value of the type can be no field and is no name, so it is
     * unknown, with the type's reason, and meets no hygiene rule. One the type admits meets §8.2 first, under
     * the type's profile, and the order is the point: a name-hygiene refusal MUST NOT be reported in one of
     * §8.1's four categories, so a look-alike field name is refused here rather than told it is unknown -- which
     * would be a verdict on the document for a policy rule, and would advise adding a field already declared.
     */
    void unmatched(JsonReadContext ctx, String memberName) {
        JsonReadContext at = ctx.field(memberName);
        Optional<String> notAName = nameRefusal(memberName);
        if (notAName.isPresent()) {
            // Under the default the message is the plain one, as for any undeclared identifier; only an author's
            // own name type is named, with its reason, since its rules are the ones to look up.
            at.report(nameReader == null ? rules.unrecognizedField(memberName)
                    : rules.unrecognizedField(memberName, nameType, notAName.get()));
        } else if (!NameHygiene.refusesMember(ctx, memberName, nameProfile)) {
            at.report(rules.unrecognizedField(memberName));
        }
        EventSkip.nextValue(at);
    }

    /** Why {@code memberName} is no value of the record's name type, or empty when it is one. */
    private Optional<String> nameRefusal(String memberName) {
        if (nameReader == null) {
            return IdentifierGrammar.validate(memberName);
        }
        try {
            nameReader.read(memberName);
            return Optional.empty();
        } catch (AtomTypeException refused) {
            return Optional.of(refused.getMessage());
        }
    }

    /**
     * §6.1.4's field groups, judged over what the document stated -- a non-null slot, before anything is
     * injected. Grouping has no wire form (§5.11), so this is the only place a group is enforced, and a member
     * written null still counts: presence is what a group counts. Per group, in one pass over its members: an
     * option is chosen when any member is stated, each chosen option reports every member it needs and lacks,
     * in option order, and then the count of chosen options is judged against the group's state.
     */
    void validateGroups(JsonReadContext ctx, Object[] slots) {
        for (GroupPlan plan : groups) {
            int chosen = 0;
            for (int o = 0; o < plan.options.length; o++) {
                int by = firstStated(plan.options[o], slots);
                if (by < 0) {
                    continue;
                }
                chosen++;
                for (int at : plan.required[o]) {
                    if (at >= 0 && slots[at] == null) {
                        ctx.report(rules.optionNeeds(names[by], plan.optionText[o], names[at]));
                    }
                }
            }
            if (plan.group.atLeastOne()) {
                if (chosen == 0) {
                    ctx.report(rules.groupRequiresAtLeastOne(plan.text));
                }
            } else if (plan.group.optional() ? chosen > 1 : chosen != 1) {
                ctx.report(!plan.group.optional()
                        ? rules.groupChoosesExactlyOne(plan.text, chosen)
                        : rules.groupChoosesAtMostOne(plan.text, chosen));
            }
        }
    }

    /** The first slot of {@code option} the document stated, or -1. */
    private static int firstStated(int[] option, Object[] slots) {
        for (int at : option) {
            if (at >= 0 && slots[at] != null) {
                return at;
            }
        }
        return -1;
    }

    boolean hasGroups() {
        return groups.length > 0;
    }

    /**
     * One field group as slots: each option's members, and the subset its group does not mark {@code ?}. A
     * member naming no field of this record is -1. The texts are the option and the group as messages show
     * them, built once so a read builds none.
     */
    private record GroupPlan(FieldGroup group, int[][] options, int[][] required, String[] optionText,
                             String text) {

        static GroupPlan of(FieldGroup group, Map<String, Integer> byName, Normalization form) {
            List<List<String>> members = group.members();
            int[][] options = new int[members.size()][];
            int[][] required = new int[members.size()][];
            String[] optionText = new String[members.size()];
            for (int o = 0; o < members.size(); o++) {
                List<String> option = members.get(o);
                options[o] = option.stream()
                        .mapToInt(member -> byName.getOrDefault(form.apply(Nfc.of(member)), -1)).toArray();
                required[o] = option.stream().filter(member -> !group.optionalMembers().contains(member))
                        .mapToInt(member -> byName.getOrDefault(form.apply(Nfc.of(member)), -1)).toArray();
                optionText[o] = String.join(" ", option);
            }
            return new GroupPlan(group, options, required, optionText, group.describe());
        }
    }
}
