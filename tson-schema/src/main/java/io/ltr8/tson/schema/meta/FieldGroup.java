package io.ltr8.tson.schema.meta;

import java.util.List;

/**
 * The meta-kernel's {@code field_group} record (Part 2 §5.11, §8.1): a resolved field group,
 * {@code state} defaulting to {@link FieldState#REQUIRED}. Not yet produced by {@code
 * SchemaResolver} -- no example resolved so far has a field group -- modeled now so {@link
 * RecordBody}'s shape doesn't need revisiting once one does.
 */
public record FieldGroup(List<String> members, FieldState state) {

    public FieldGroup {
        members = List.copyOf(members);
    }
}
