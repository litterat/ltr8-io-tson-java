package io.ltr8.tson.json.reader;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.unicode.Nfc;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Bind mode's <b>labelled choice</b>: a record whose fields form one REQUIRED group, bound to a sealed interface
 * whose members each carry one of those fields as their only component. The kernel's {@code type_argument} is the
 * shape -- {@code { ( name: type_ref | value: value ) }}.
 *
 * <p><b>The present field is the discriminator.</b> There is no {@code $type} to dispatch on, so the record is
 * read by {@link RecordReader} the ordinary way -- §6.1.4's group rule admitting exactly one member -- and the
 * member built is the one whose field arrived. Why a union rather than a record of optional components: the
 * union is what lets a recursive shape bind at all, {@code type_argument} holding a {@code type_ref} whose
 * arguments hold {@code type_argument}s right back.
 *
 * <p><b>A member is matched to its field by its one component's wire name</b> ({@code @Field} where present, the
 * component name otherwise): a member of a labelled choice carries exactly the field it is the label for. Every
 * part of the match is checked when the reader is built, and a near-miss is a {@link BindMismatchException}
 * naming what did not line up -- guessing at a partial match would bind a member to a field it does not carry.
 */
final class BindGroupUnionBuilder implements RecordBuilder {

    /** The record at {@code name} bound to {@code union} as a labelled choice, or the mismatch that stops it. */
    static JsonTypeReader<?> reader(String name, TypeDefinition definition, ValueReaderContext context,
                                   DataBindContext binding, DataClassUnion union) {
        RecordPlan plan = new RecordPlan(name, definition, context);
        DataClassRecord[] members = new DataClassRecord[plan.names.length];
        List<String> mismatches = new ArrayList<>();
        String shape = shapeMismatch(plan, definition, union);
        if (shape != null) {
            mismatches.add(shape);
        } else {
            for (Class<?> memberType : union.memberTypes()) {
                DataClass member;
                try {
                    member = binding.getDescriptor(memberType);
                } catch (DataBindException e) {
                    mismatches.add("member " + memberType.getName() + " does not bind: " + e.getMessage());
                    continue;
                }
                if (!(member instanceof DataClassRecord record) || record.fields().length != 1) {
                    mismatches.add("member " + memberType.getName() + " is not a record of one component, the "
                            + "field it is the label for");
                    continue;
                }
                int at = plan.slotOf(Nfc.of(record.fields()[0].name()));
                if (at < 0 || members[at] != null) {
                    mismatches.add("member " + memberType.getName() + "'s component '" + record.fields()[0].name()
                            + "' labels " + (at < 0 ? "no field of the record" : "a field another member labels"));
                    continue;
                }
                members[at] = record;
            }
        }
        JsonTypeReader<?>[] readers = plan.schemaReaders.clone();
        if (mismatches.isEmpty()) {
            for (int i = 0; i < readers.length; i++) {
                String component = members[i].fields()[0].name();
                readers[i] = BindTargets.to(readers[i], members[i].fields()[0].dataClass(), "field '"
                        + plan.fields[i].name() + "'", "component '" + component + "' of "
                        + members[i].typeClass().getSimpleName(), mismatches);
            }
        }
        if (!mismatches.isEmpty()) {
            throw new BindMismatchException("'" + plan.displayName + "' is a record, and "
                    + union.typeClass().getName() + ", which is bound to it, is a union: a record with no subtypes "
                    + "binds to one only as a labelled choice -- one REQUIRED group over every field, each member "
                    + "a record carrying one of them -- and " + String.join("; ", mismatches));
        }
        return new RecordReader(plan, readers, new Object[readers.length], new BindGroupUnionBuilder(members));
    }

    /** What stops the record's own shape being a labelled choice for {@code union}, or null where nothing does. */
    private static String shapeMismatch(RecordPlan plan, TypeDefinition definition, DataClassUnion union) {
        List<FieldGroup> groups = ((RecordBody) definition.body()).groups();
        if (groups.size() != 1) {
            return "the record has " + groups.size() + " groups";
        }
        FieldGroup group = groups.getFirst();
        if (group.state() != ElementState.REQUIRED) {
            return "its group is optional, so a record with no field present has no member to be";
        }
        if (group.members().size() != plan.names.length) {
            return "its group covers " + group.members().size() + " of its " + plan.names.length + " fields";
        }
        if (union.memberTypes().length != plan.names.length) {
            return "the union has " + union.memberTypes().length + " members for " + plan.names.length
                    + " fields";
        }
        return null;
    }

    /** The member each field slot selects. */
    private final DataClassRecord[] members;

    private BindGroupUnionBuilder(DataClassRecord[] members) {
        this.members = members;
    }

    @Override
    public Object build(JsonReadContext ctx, Object[] slots, boolean clean) {
        if (!clean) {
            return null;
        }
        // A clean read passed the REQUIRED group's rule, so exactly one slot is filled.
        for (int i = 0; i < slots.length; i++) {
            Object slot = slots[i];
            if (slot == null) {
                continue;
            }
            Object value = slot == Slots.ABSENT || slot == Slots.NULL_KEPT ? null : slot;
            try {
                Object built = members[i].constructor().invoke(new Object[] {value});
                return members[i].bridge().isPresent() ? members[i].bridge().get().toObject().invoke(built) : built;
            } catch (Throwable e) {
                BindRecordBuilder.rejected(ctx, members[i].typeClass(), e);
                return null;
            }
        }
        return null;
    }

    @Override
    public Object refused() {
        return null;
    }
}
