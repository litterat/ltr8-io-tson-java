package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.RecordBody;

import java.util.List;
import java.util.Set;

/**
 * Which reader a record position gets, decided once from {@code record.extension} ([TSON-SCHEMA] §5.2).
 *
 * <p><b>A decorator over the mode's own record factory rather than a branch inside it.</b> ABSTRACT and
 * SEALED positions do not read a record at all -- they place the value and hand it to the selected member's
 * reader -- so they need neither mode's field machinery, and one dispatcher serves both on {@link
 * ChoiceReader}'s reasoning. What is left is the concrete case, which is exactly what the mode's factory
 * already built, unchanged.
 *
 * <p>OPEN and FINAL share that concrete reader because they read identically. The difference between them is
 * only which names a tag may carry -- the subtype set -- and a FINAL record's is empty by construction, so
 * nothing asks whether a record is final and no reader carries a check for it.
 *
 * <p><b>Both dispatchers compare a written tag against flattened names</b> ({@link Subsumption#admitting}),
 * §7.2 comparing "after reference flattening of both". It is not a nicety here: a family whose base is a
 * template has minted entries for every member, and §8.2 makes a minted name non-normative -- so an alias is
 * the only name a document has for {@code ok<text>}, and a dispatcher matching raw subtype names would refuse
 * every value in such a family.
 */
final class RecordDispatch {

    private RecordDispatch() {
    }

    /** {@code concrete} for an OPEN or FINAL record, a dispatcher for the two that select a member. */
    static ValueReaderFactory over(ValueReaderFactory concrete) {
        return (name, definition, context) -> {
            if (!(definition.body() instanceof RecordBody body)) {
                return concrete.create(name, definition, context);
            }
            String displayName = EntryDisplayName.of(name, definition, context.schema().entries());
            Set<String> selfNames = Subsumption.admitting(List.of(name), context.namesMeaning());
            return switch (body.extension()) {
                case ABSTRACT -> new RecordTagDispatchReader(selfNames, displayName,
                        Subsumption.admitting(definition.subtypes(), context.namesMeaning()), context.readers());
                // The sealed reader takes its subtypes raw: it maps each member's pins to that member, so an
                // alias is not a second member. Where it compares a written tag it admits aliases (`deeper`).
                case SEALED -> new RecordMemberDispatchReader(selfNames, displayName, body,
                        Set.copyOf(definition.subtypes()), context, context.readers());
                case OPEN, FINAL -> concrete.create(name, definition, context);
            };
        };
    }
}
