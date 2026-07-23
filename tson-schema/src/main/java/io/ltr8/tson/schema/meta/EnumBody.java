package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.util.List;

/**
 * The meta-kernel's {@code enum} constructor's own vocabulary, resolved (Part 2 §4.1, §8.1):
 * {@code members: set<token>} -- backs {@code boolean} (`[true false]`), the kernel's own internal
 * enumerations ({@code product_access_type}, {@code field_state}, {@code type_kind}, ...), and
 * every user-declared {@code !enum [...]} instance. Kept as an ordered {@code List}, matching how
 * {@link TypeDefinition#supertypes}/{@link TypeDefinition#subtypes} already represent conceptual
 * sets -- member order is preserved for deterministic output, not semantically significant.
 */
@Typename(name = "enum")
public record EnumBody(List<String> members) implements TypeBody {

    public EnumBody {
        members = List.copyOf(members);
    }
}
