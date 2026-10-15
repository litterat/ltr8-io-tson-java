package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A meta-layer body that returns its by-name references from {@link #references()}, as a type reference would be --
 * the migration mistake one host shape invites. {@code ValuesByNameTest} shows it harmless: a reference read by name
 * is judged against its slot whichever list the class puts it in.
 */
@Typename(name = "filed_in_references")
public record FiledInReferences(TypeRef request, TypeRef method) implements Data {

    @Override
    public List<TypeRef> references() {
        return Stream.of(request, method).filter(Objects::nonNull).toList();
    }
}
