package io.ltr8.tson.parser.ast.schema;

import io.ltr8.tson.parser.ast.Annotation;

import java.util.List;

/**
 * {@code group-def = *annotation "(" ws group-member 1*(ws "|" ws group-member) ws ")" ["?"]}
 * (Part 2 §12.1, §5.11) -- a field group: mutually exclusive labelled members occupying one
 * logical record position. {@code optional}: a bare group is {@code REQUIRED} (exactly one member
 * MUST be present); with {@code ?}, {@code OPTIONAL} (at most one MAY be present).
 */
public record GroupDef(List<Annotation> annotations, List<Member> members, boolean optional) implements RecordEntry {

    public GroupDef {
        annotations = List.copyOf(annotations);
        members = List.copyOf(members);
        if (members.size() < 2) {
            throw new IllegalArgumentException("a field group requires at least two members, got " + members.size());
        }
    }

    /** {@code group-member = *annotation field-name ws ":" ws type-ref} -- bare: no {@code ?}, no value modifier. */
    public record Member(List<Annotation> annotations, String name, TypeRef typeRef) {

        public Member {
            annotations = List.copyOf(annotations);
        }
    }
}
