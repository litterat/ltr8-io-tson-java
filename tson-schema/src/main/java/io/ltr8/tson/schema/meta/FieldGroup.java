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

    /**
     * Whether this is the at-least-one group: one REQUIRED option, which the grammar writes only as {@code +}
     * over its members (SPEC-FEEDBACK.md #18).
     */
    public boolean atLeastOne() {
        return members.size() == 1 && state == ElementState.REQUIRED;
    }

    /**
     * The group as its spelling reads, for a message: options separated by {@code |}, a member marked {@code ?}
     * where it is optional within its option, and the at-least-one group as its members -- {@code include |
     * name? type?}, {@code email | phone}.
     */
    public String describe() {
        if (atLeastOne()) {
            return String.join(" | ", members.getFirst());
        }
        return String.join(" | ", members.stream().map(option -> String.join(" ", option.stream()
                .map(member -> optional.contains(member) ? member + "?" : member).toList())).toList());
    }

    /** Whether {@code field} is a member of this group. */
    public boolean hasMember(String field) {
        return members.stream().anyMatch(option -> option.contains(field));
    }
}
