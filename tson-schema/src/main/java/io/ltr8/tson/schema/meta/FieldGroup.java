package io.ltr8.tson.schema.meta;

import java.util.List;

/**
 * The meta-kernel's {@code field_group} record (Part 2 §5.11, §8.1): a resolved field group. {@code members} holds
 * the group's options in source order, each the fields it holds; {@code optional} names the members that may be
 * left out once their option is chosen. An option is chosen when any of its members is present, a chosen option
 * holds every member {@code optional} does not name, and {@code state} counts chosen options:
 * {@link ElementState#REQUIRED} (the default) admits exactly one, {@link ElementState#OPTIONAL} at most one.
 */
public record FieldGroup(List<List<String>> members, List<String> optional, ElementState state) {

    public FieldGroup {
        members = members.stream().<List<String>>map(List::copyOf).toList();
        optional = optional == null ? List.of() : List.copyOf(optional);
    }

    /** A group whose every option is one field: the exactly-one and at-most-one forms. */
    public static FieldGroup ofSingles(List<String> members, ElementState state) {
        return new FieldGroup(members.stream().<List<String>>map(List::of).toList(), List.of(), state);
    }

    /** Every member of every option, in source order. */
    public List<String> memberNames() {
        return members.stream().flatMap(List::stream).toList();
    }

    /** Whether {@code field} is a member of this group. */
    public boolean hasMember(String field) {
        return members.stream().anyMatch(option -> option.contains(field));
    }
}
