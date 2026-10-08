package io.ltr8.tson.compiler.ast.schema;

import io.ltr8.tson.compiler.ast.Annotation;
import io.ltr8.tson.schema.meta.FieldGroup;

import java.util.List;

/**
 * {@code group-def = *annotation "(" ws group-option *(ws "|" ws group-option) ws ")" ["?" / "+"]} (Part 2
 * §12.1, §5.11) -- a field group: a presence rule over the fields its options hold. An
 * option is chosen when any of its members is present, and a chosen option holds every member whose name is
 * not marked {@code ?}. A bare group admits exactly one chosen option, {@code ?} at most one, and {@code +}
 * at least one of its members, each option then being one field.
 *
 * <p>This is the group as written. Which shapes the grammar refuses is the parser's; what the group lowers to
 * is {@link #fieldGroup}.
 */
public record GroupDef(List<Annotation> annotations, List<List<Member>> options, Quantifier quantifier)
        implements RecordEntry {

    /** The mark after the closing {@code )}. */
    public enum Quantifier {
        /** Bare: exactly one option chosen. */
        EXACTLY_ONE,
        /** {@code ?}: at most one option chosen. */
        AT_MOST_ONE,
        /** {@code +}: at least one member present, every option being one field. */
        AT_LEAST_ONE
    }

    public GroupDef {
        annotations = List.copyOf(annotations);
        options = options.stream().<List<Member>>map(List::copyOf).toList();
        if (options.isEmpty() || options.stream().anyMatch(List::isEmpty)) {
            throw new IllegalArgumentException("a field group has at least one option, and every option a member");
        }
    }

    /** Every member of every option, in source order. */
    public List<Member> members() {
        return options.stream().flatMap(List::stream).toList();
    }

    /**
     * The kernel's {@code field_group} for this group. {@code +} is sugar: {@code ( a: A | b: B )+} is the
     * non-optional group of one option whose members are all optional within it, so at least one is present.
     */
    public FieldGroup fieldGroup() {
        if (quantifier == Quantifier.AT_LEAST_ONE) {
            List<String> names = members().stream().map(Member::name).toList();
            return new FieldGroup(List.of(names), names, false);
        }
        return new FieldGroup(
                options.stream().map(option -> option.stream().map(Member::name).toList()).toList(),
                members().stream().filter(Member::omittable).map(Member::name).toList(),
                quantifier == Quantifier.AT_MOST_ONE);
    }

    /**
     * {@code group-member = *annotation field-name ["?"] ws ":" ws type-ref ["?"]}. The name's {@code ?}
     * ({@code omittable}) makes the member optional once its option is chosen; the type's ({@code voidable})
     * admits {@code _}, and a voidable member written {@code _} is present and chooses its option. A member
     * takes no value modifier, since a group never supplies a member.
     */
    public record Member(List<Annotation> annotations, String name, boolean omittable, TypeRef typeRef,
                         boolean voidable) {

        public Member {
            annotations = List.copyOf(annotations);
        }
    }
}
