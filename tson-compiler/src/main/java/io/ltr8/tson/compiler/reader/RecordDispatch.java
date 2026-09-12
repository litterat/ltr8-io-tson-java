package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.RecordBody;

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
            Set<String> subtypes = Set.copyOf(definition.subtypes());
            return switch (body.extension()) {
                case ABSTRACT -> new RecordTagDispatchReader(name, displayName, subtypes, context.readers());
                case SEALED -> new RecordMemberDispatchReader(name, displayName, body, subtypes,
                        context.schema().entries(), context.readers());
                case OPEN, FINAL -> concrete.create(name, definition, context);
            };
        };
    }
}
