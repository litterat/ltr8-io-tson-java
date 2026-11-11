package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.FamilySelectors;
import io.ltr8.tson.schema.meta.RecordBody;

import java.util.List;
import java.util.Set;

/**
 * Which reader a record position gets, decided once from {@code record.extension} ([TSON-SCHEMA] §5.2).
 *
 * <p><b>A decorator over the mode's own record factory rather than a branch inside it.</b> An ABSTRACT position,
 * dispatching by tag or on its members' discriminators, does not read a record at all -- it places the value and hands
 * it to the selected member's reader -- so it needs neither mode's field machinery, and one dispatcher serves both on
 * {@link ChoiceReader}'s reasoning. What is left is the concrete case, which is exactly what the mode's factory already
 * built, unchanged.
 *
 * <p>OPEN and FINAL share that concrete reader because they read identically. The difference between them is
 * only which names a tag may carry -- the subtype set -- and a FINAL record's is empty by construction, so
 * nothing asks whether a record is final and no reader carries a check for it.
 *
 * <p><b>Both dispatchers compare a written tag against flattened names</b> ({@link Subsumption#admitting}),
 * §7.2 comparing "after alias flattening of both": a tag naming an alias of the base or of a member names that
 * type, and a dispatcher matching raw names would refuse it.
 */
final class RecordDispatch {

    private RecordDispatch() {
    }

    /** {@code concrete} for an OPEN or FINAL record, a dispatcher for the two that select a member. */
    static ValueReaderFactory over(ValueReaderFactory concrete) {
        return (name, definition, context) -> {
            if (!(definition instanceof RecordBody body)) {
                return concrete.create(name, definition, context);
            }
            String displayName = EntryDisplayName.of(name, context.schema());
            // A record written in place has no name, so no tag names it ([TSON-SCHEMA] §7.2).
            Set<String> selfNames = context.tagOf(name).isPresent()
                    ? Subsumption.admitting(List.of(name), context.namesMeaning()) : Set.of();
            return switch (body.extension()) {
                // ABSTRACT says the record has no values of its own; whether its members are placed by the
                // tag or by reading their pins is the body's own `discriminators` and never a second member
                // of the enum. The member reader takes its subtypes raw: it maps each member's pins to that
                // member, so an alias is not a second member. Where it compares a written tag it admits
                // aliases (`deeper`).
                case ABSTRACT -> FamilySelectors.dispatchesOnMembers(definition)
                        ? new RecordMemberDispatchReader(selfNames, displayName,
                                FamilySelectors.of(definition),
                                Set.copyOf(context.subtypesOf(name, definition)), context, context.readers())
                        : tagDispatch(selfNames, displayName,
                                Subsumption.admitting(context.subtypesOf(name, definition), context.namesMeaning()),
                                context.instantiableSubtypesOf(name, definition), context);
                case OPEN, FINAL -> concrete.create(name, definition, context);
            };
        };
    }

    /** A tag dispatcher over {@code subtypes}, a namespace type among them read as an instance of its constructor. */
    private static RecordTagDispatchReader tagDispatch(Set<String> selfNames, String displayName, Set<String> subtypes,
                                                       List<String> named, ValueReaderContext context) {
        return new RecordTagDispatchReader(selfNames, displayName, subtypes, named, context.readers());
    }
}
