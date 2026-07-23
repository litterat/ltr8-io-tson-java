package io.ltr8.tson.parser.ast.schema;

import java.util.List;

/**
 * {@code record-def = "{" ws [record-entry *(separator record-entry)] ws "}"} (Part 2 §12.1,
 * §5.2) -- a braced record body: fresh (no supertypes), a refinement's tightening body, or a
 * construction's trailing body. An empty {@code {}} is the zero-field case.
 */
public record RecordDef(List<RecordEntry> entries) implements StructuralDef {

    public RecordDef {
        entries = List.copyOf(entries);
    }
}
