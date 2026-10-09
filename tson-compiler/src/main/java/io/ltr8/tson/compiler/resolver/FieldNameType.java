package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.Optional;
import java.util.function.Function;

/**
 * What a record's {@code field_name_type} names (SPEC-FEEDBACK.md #10): the family its field names belong to, the
 * peer of {@link EnumLabelType} for an enum's {@code type}. The kernel's default, {@code field_name}, was written by
 * the {@code record} constructor and resolves where that constructor is declared, the structure namespace; a type an
 * author writes resolves in the author's own namespace first.
 */
public final class FieldNameType {

    private FieldNameType() {
    }

    /** The resolved family and the namespace its own references resolve in, or empty where it names nothing. */
    public static Optional<EnumLabelType.Resolved> of(TypeDefinition record, Function<String, TypeDefinition> local,
                                                      Function<String, TypeDefinition> structure) {
        if (!(record.body() instanceof RecordBody body)) {
            return Optional.empty();
        }
        String name = body.fieldNameType();
        Function<String, TypeDefinition> first = name.equals(RecordBody.FIELD_NAME) ? structure : local;
        Function<String, TypeDefinition> second = first == structure ? local : structure;
        for (Function<String, TypeDefinition> namespace : java.util.List.of(first, second)) {
            if (namespace.apply(name) != null) {
                Function<String, TypeDefinition> lookup = n -> {
                    TypeDefinition found = namespace.apply(n);
                    return found != null ? found : (namespace == first ? second : first).apply(n);
                };
                return Optional.of(new EnumLabelType.Resolved(
                        namespace.apply(ReferenceChain.terminal(name, namespace)), lookup));
            }
        }
        return Optional.empty();
    }
}
