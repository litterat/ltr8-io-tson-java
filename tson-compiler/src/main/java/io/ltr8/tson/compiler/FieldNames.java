package io.ltr8.tson.compiler;

import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.compiler.reader.ValueIdentity;
import io.ltr8.tson.compiler.resolver.NameType;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.unicode.IdentifierType;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.NamespaceBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeInline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * What a record's {@code name_type} obliges ([TSON-SCHEMA] §5.2): that it names an identifier family, a member of
 * {@code meta.identifier_ref} ({@link NameType#admits}, [TSON-SCHEMA] §4.2), that every field name the record states
 * -- in {@code fields}, {@code discriminators} and its groups -- is a value of it, and that no two of its fields are
 * one value under its equality. The peer of {@link EnumLabels}, on the same terms: a
 * checker over the linked closure that hands back violations and leaves reporting to the linker.
 *
 * <p><b>Two fields of one spelling never reach here.</b> {@code record.fields} is a map keyed by the name, so a name
 * written twice is the map's duplicate key, refused where the record is read or resolved. What is left for this
 * check is two spellings that are one name under the type's equality -- {@code NAME} and {@code name} under a
 * case-folding type -- which only a resolved name type can decide.
 *
 * <p><b>A record written in place is judged as a declared one is</b> ([TSON-SCHEMA] §5.2), under its own name type,
 * its fields its own scope: it is found where it stands in a declaration ({@link #inPlace}), and refused against that
 * declaration.
 */
final class FieldNames {

    private FieldNames() {
    }

    /**
     * The definition {@code record}'s field names are read by, where its field name type is not the kernel's
     * default -- what {@link io.ltr8.tson.schema.TsonLinkedSchema#nameTypes} carries to its readers. An open
     * record's too: no reader reads a value against it, and its member dispatch matches the selectors' keys in
     * the form its members read them. Empty for the default, which every reader applies without being told, and
     * for a type that resolves to nothing.
     */
    static Optional<Top> readBy(Top record, Map<String, Top> merged,
                                           Function<String, Top> structure) {
        if (!(record instanceof RecordBody body) || body.nameType().isEmpty()) {
            return Optional.empty();
        }
        return NameType.of(record, Optional.empty(), merged::get, structure).map(NameType.Resolved::definition);
    }

    /** Every violation among the records in {@code localNames}, in their order. */
    static List<EnumLabels.Violation> check(Map<String, Top> merged, Set<String> localNames,
                                            Function<String, Top> structure) {
        List<EnumLabels.Violation> violations = new ArrayList<>();
        for (String name : localNames) {
            Top definition = merged.get(name);
            // An open record's field names are each application's, so they are judged once, here.
            Optional<EnumLabels.Violation> violation = definition instanceof RecordBody record
                    ? check(name, "'" + name + "'", definition, record, merged, structure) : Optional.empty();
            for (TypeInline inline : inPlace(definition)) {
                violation = violation.or(() -> check(name, inPlace(name, inline), inline.of(), (RecordBody) inline.of(),
                        merged, structure));
            }
            violation.ifPresent(violations::add);
        }
        return List.copyOf(violations);
    }

    /**
     * Every record written in place {@code definition} holds, at any depth, in the order written: at a field's type and
     * inside a container, a tuple, a choice or another record in place. A record an application of an open record
     * closes to is left out with everything it holds: its names are the open record's, judged where it is declared.
     */
    static List<TypeInline> inPlace(Top definition) {
        List<TypeInline> found = new ArrayList<>();
        holdsInPlace(definition, found);
        return found;
    }

    /** How a message names a record in place {@code entry} holds. */
    static String inPlace(String entry, TypeInline inline) {
        return "the record written in place '" + inline.text() + "' in '" + entry + "'";
    }

    private static void holdsInPlace(Top body, List<TypeInline> found) {
        switch (body) {
            case RecordBody record -> record.fields().values().forEach(field -> slotInPlace(field.type(), found));
            case ArrayBody array -> slotInPlace(array.elementType(), found);
            case MapBody map -> slotInPlace(map.valueType(), found);
            case NamespaceBody namespace -> slotInPlace(namespace.valueType(), found);
            case TupleBody tuple -> tuple.elements().forEach(element -> slotInPlace(element.elementType(), found));
            case ChoiceBody choice -> choice.variants().forEach(variant -> slotInPlace(variant, found));
            default -> {
            }
        }
    }

    private static void slotInPlace(TypeExpr slot, List<TypeInline> found) {
        if (!(slot instanceof TypeInline inline) || inline.closes().isPresent()) {
            return;
        }
        if (inline.of() instanceof RecordBody) {
            found.add(inline);
        }
        holdsInPlace(inline.of(), found);
    }

    /** {@code body}'s violation, reported against {@code name} and named in its message as {@code who}. */
    private static Optional<EnumLabels.Violation> check(String name, String who, Top definition, RecordBody body,
                                                        Map<String, Top> merged,
                                                        Function<String, Top> structure) {
        Optional<NameType.Resolved> resolved = NameType.of(definition, Optional.empty(), merged::get, structure);
        if (resolved.isEmpty() && body.nameType().isEmpty()) {
            // The default where no kernel is in scope to resolve it: the identifier grammar it stands for.
            for (String field : body.fields().keySet()) {
                Optional<String> violation = io.ltr8.tson.atom.IdentifierGrammar.validate(field);
                if (violation.isPresent()) {
                    return Optional.of(new EnumLabels.Violation(name, who + ": field name '" + field
                            + "' is not an identifier: " + violation.get()));
                }
            }
            return Optional.empty();
        }
        if (resolved.isEmpty()) {
            return Optional.of(new EnumLabels.Violation(name, who + ": its name_type '"
                    + body.nameTypeName() + "' names nothing in scope"));
        }
        NameType.Resolved type = resolved.get();
        if (!NameType.admits(definition, Optional.empty(), type, merged::get, structure)) {
            return Optional.of(new EnumLabels.Violation(name, who + ": its name_type '"
                    + body.nameTypeName() + "' is not an identifier family -- a record's field names are names drawn "
                    + "from an atom-family instance whose constructor IS-A identifier_type; a key that is no name "
                    + "belongs in a map"));
        }
        if (type.definition() instanceof IdentifierType identifiers) {
            Optional<String> form = NameType.formRefusal(identifiers);
            if (form.isPresent()) {
                return Optional.of(new EnumLabels.Violation(name, who + ": its name_type '"
                        + body.nameTypeName() + "' cannot judge names -- " + form.get()));
            }
        }
        Optional<AtomType<?>> parser = AtomParsers.forType(type.definition());
        if (parser.isEmpty()) {
            return Optional.empty();
        }
        // Under the default, meta.field_name, a refusal speaks of identifiers as any name's would; only an author's own name
        // type is named, since its rules are the ones the author has to look up.
        boolean identifier = NameType.isIdentifier(type, structure);
        String ofType = identifier ? "an identifier" : "a value of its name_type '" + body.nameTypeName() + "'";
        Map<Object, String> seen = new HashMap<>();
        for (String field : body.fields().keySet()) {
            Optional<EnumLabels.Violation> problem = read(name, who, ofType, parser.get(), field);
            if (problem.isPresent()) {
                return problem;
            }
            String earlier = seen.putIfAbsent(ValueIdentity.of(parser.get().read(field)), field);
            if (earlier != null) {
                return Optional.of(new EnumLabels.Violation(name, who + ": fields '" + earlier + "' and '"
                        + field + "' are one " + (identifier ? "field name"
                                : "value of its name_type '" + body.nameTypeName() + "'")));
            }
        }
        Set<String> referenced = new LinkedHashSet<>(body.discriminators());
        for (FieldGroup group : body.groups()) {
            group.members().forEach(referenced::addAll);
            group.optionalMembers().forEach(referenced::add);
        }
        for (String reference : referenced) {
            Optional<EnumLabels.Violation> problem = read(name, who, ofType, parser.get(), reference);
            if (problem.isPresent()) {
                return problem;
            }
        }
        return Optional.empty();
    }

    private static Optional<EnumLabels.Violation> read(String name, String who, String ofType, AtomType<?> parser,
                                                       String fieldName) {
        try {
            parser.read(fieldName);
            return Optional.empty();
        } catch (AtomTypeException refused) {
            return Optional.of(new EnumLabels.Violation(name, who + ": field name '" + fieldName
                    + "' is not " + ofType + ": " + refused.getMessage()));
        }
    }
}
