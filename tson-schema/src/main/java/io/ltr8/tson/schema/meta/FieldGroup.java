package io.ltr8.tson.schema.meta;

import java.util.List;

/**
 * The meta-kernel's {@code field_group} record (Part 2 §5.11, §8.1): a resolved field group,
 * {@code state} defaulting to {@link ElementState#REQUIRED} -- a bare group is REQUIRED (exactly
 * one member MUST be present), {@code ?} makes it OPTIONAL (at most one MAY be present); these are
 * the only two group states in v1, hence {@link ElementState} (the two-member enum shared with
 * array/tuple positions), not {@link FieldState}'s five members -- matching the kernel's own
 * {@code state: element_state ~ REQUIRED} field type exactly.
 */
public record FieldGroup(List<String> members, ElementState state) {

    public FieldGroup {
        members = List.copyOf(members);
    }
}
