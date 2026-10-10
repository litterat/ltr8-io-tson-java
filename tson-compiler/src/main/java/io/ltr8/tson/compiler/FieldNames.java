package io.ltr8.tson.compiler;

import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.compiler.reader.ValueIdentity;
import io.ltr8.tson.compiler.resolver.NameType;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * What a record's {@code name_type} obliges (SPEC-FEEDBACK.md #10): that it names an identifier family, that every
 * field name the record states -- in {@code fields}, {@code discriminators} and its groups -- is a value of it, and
 * that no two of its fields are one value under its equality. The peer of {@link EnumLabels}, on the same terms: a
 * checker over the linked closure that hands back violations and leaves reporting to the linker.
 */
final class FieldNames {

    private FieldNames() {
    }

    /**
     * The definition {@code record}'s field names are read by, where its field name type is not the kernel's
     * default -- what {@link io.ltr8.tson.schema.TsonLinkedSchema#nameTypes} carries to its readers. Empty
     * for the default, which every reader applies without being told, and for a type that resolves to nothing.
     */
    static Optional<TypeDefinition> readBy(TypeDefinition record, Map<String, TypeDefinition> merged,
                                           Function<String, TypeDefinition> structure) {
        if (record == null || !(record.body() instanceof RecordBody body)
                || body.nameType().equals(RecordBody.FIELD_NAME)) {
            return Optional.empty();
        }
        return NameType.of(record, merged::get, structure).map(NameType.Resolved::definition);
    }

    /** Every violation among the records in {@code localNames}, in their order. */
    static List<EnumLabels.Violation> check(Map<String, TypeDefinition> merged, Set<String> localNames,
                                            Function<String, TypeDefinition> structure) {
        List<EnumLabels.Violation> violations = new ArrayList<>();
        for (String name : localNames) {
            TypeDefinition definition = merged.get(name);
            if (definition != null && definition.body() instanceof RecordBody body) {
                check(name, definition, body, merged, structure).ifPresent(violations::add);
            }
        }
        return List.copyOf(violations);
    }

    private static Optional<EnumLabels.Violation> check(String name, TypeDefinition definition, RecordBody body,
                                                        Map<String, TypeDefinition> merged,
                                                        Function<String, TypeDefinition> structure) {
        Optional<NameType.Resolved> resolved = NameType.of(definition, merged::get, structure);
        if (resolved.isEmpty() && body.nameType().equals(RecordBody.FIELD_NAME)) {
            // The default where no kernel is in scope to resolve it: the identifier grammar it stands for.
            for (RecordField field : body.fields()) {
                Optional<String> violation = io.ltr8.tson.atom.IdentifierGrammar.validate(field.name());
                if (violation.isPresent()) {
                    return Optional.of(new EnumLabels.Violation(name, "'" + name + "': field name '" + field.name()
                            + "' is not a value of its name_type 'field_name': " + violation.get()));
                }
            }
            return Optional.empty();
        }
        if (resolved.isEmpty()) {
            return Optional.of(new EnumLabels.Violation(name, "'" + name + "': its name_type '"
                    + body.nameType() + "' names nothing in scope"));
        }
        NameType.Resolved type = resolved.get();
        if (!EnumLabels.isFamily(type.definition(), EnumLabels.IDENTIFIER_TYPE, type.lookup())) {
            return Optional.of(new EnumLabels.Violation(name, "'" + name + "': its name_type '"
                    + body.nameType() + "' is not an identifier family -- a record's field names are names drawn "
                    + "from an atom-family instance whose constructor IS-A identifier_type; a key that is no name "
                    + "belongs in a map"));
        }
        Optional<AtomType<?>> parser = AtomParsers.forType(type.definition().body());
        if (parser.isEmpty()) {
            return Optional.empty();
        }
        Map<Object, String> seen = new HashMap<>();
        for (RecordField field : body.fields()) {
            Optional<EnumLabels.Violation> problem = read(name, body, parser.get(), field.name());
            if (problem.isPresent()) {
                return problem;
            }
            String earlier = seen.putIfAbsent(ValueIdentity.of(parser.get().read(field.name())), field.name());
            if (earlier != null && !earlier.equals(field.name())) {
                return Optional.of(new EnumLabels.Violation(name, "'" + name + "': fields '" + earlier + "' and '"
                        + field.name() + "' are one value of its name_type '" + body.nameType() + "'"));
            }
        }
        Set<String> referenced = new LinkedHashSet<>(body.discriminators());
        for (FieldGroup group : body.groups()) {
            group.members().forEach(referenced::addAll);
            group.optionalMembers().forEach(referenced::add);
        }
        for (String reference : referenced) {
            Optional<EnumLabels.Violation> problem = read(name, body, parser.get(), reference);
            if (problem.isPresent()) {
                return problem;
            }
        }
        return Optional.empty();
    }

    private static Optional<EnumLabels.Violation> read(String name, RecordBody body, AtomType<?> parser,
                                                       String fieldName) {
        try {
            parser.read(fieldName);
            return Optional.empty();
        } catch (AtomTypeException refused) {
            return Optional.of(new EnumLabels.Violation(name, "'" + name + "': field name '" + fieldName
                    + "' is not a value of its name_type '" + body.nameType() + "': "
                    + refused.getMessage()));
        }
    }
}
