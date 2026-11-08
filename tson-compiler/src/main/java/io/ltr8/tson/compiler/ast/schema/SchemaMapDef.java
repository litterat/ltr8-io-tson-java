package io.ltr8.tson.compiler.ast.schema;

import java.util.List;

/**
 * {@code "!schema" ws schema-map} as a declaration's value: a schema map nested as an entry, {@code geo => !schema {
 * address => { … } }}. It is a value of the kernel's {@code schema} namespace type, and every value of {@code schema}
 * declares as the document's root does: its members are declarations, which lift, mint, link and compile as root
 * entries do. The {@code !schema} tag is read as a spelling, as the root's is.
 *
 * <p><b>The members are not held here.</b> The parser puts each into the document's one declaration map under its
 * path from the root ({@code geo.address}), which is the member's identity and the name every later phase addresses it
 * by; a further nested map's members are keyed the same way, one segment deeper. This node is what the entry itself
 * declares: the names of its members, unqualified, in the order written.
 *
 * @param members the members' own names, in source order; never empty, a schema map declaring at least one entry
 */
public record SchemaMapDef(List<String> members) implements TypeDef {

    public SchemaMapDef {
        members = List.copyOf(members);
        if (members.isEmpty()) {
            throw new IllegalArgumentException("a schema map requires at least one declaration");
        }
    }
}
