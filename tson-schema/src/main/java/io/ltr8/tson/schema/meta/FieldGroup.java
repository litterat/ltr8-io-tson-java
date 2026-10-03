package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;

import java.util.List;

/**
 * The meta-kernel's {@code field_group} record (Part 2 §5.11, §8.1): a resolved field group. {@code members} holds
 * the group's options in source order, each the fields it holds; {@code optionalMembers} names the members that may
 * be left out once their option is chosen. An option is chosen when any of its members is present, and a chosen
 * option holds every member {@code optionalMembers} does not name. Without {@code optional} exactly one option is
 * chosen; with it, at most one, so the group as a whole may be left out, as an optional field's key may.
 */
public record FieldGroup(List<List<String>> members, @Field("optional_members") List<String> optionalMembers,
                         boolean optional) {

    public FieldGroup {
        members = members.stream().<List<String>>map(List::copyOf).toList();
        optionalMembers = optionalMembers == null ? List.of() : List.copyOf(optionalMembers);
    }

    /** A group whose every option is one field: the exactly-one and at-most-one forms. */
    public static FieldGroup ofSingles(List<String> members, boolean optional) {
        return new FieldGroup(members.stream().<List<String>>map(List::of).toList(), List.of(), optional);
    }

    /** Every member of every option, in source order. */
    public List<String> memberNames() {
        return members.stream().flatMap(List::stream).toList();
    }

    /**
     * Whether this is the at-least-one group: one option the group may not leave out, which the grammar writes
     * only as {@code +} over its members (SPEC-FEEDBACK.md #18).
     */
    public boolean atLeastOne() {
        return members.size() == 1 && !optional;
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
                .map(member -> optionalMembers.contains(member) ? member + "?" : member).toList())).toList());
    }

    /** Whether {@code field} is a member of this group. */
    public boolean hasMember(String field) {
        return members.stream().anyMatch(option -> option.contains(field));
    }
}
