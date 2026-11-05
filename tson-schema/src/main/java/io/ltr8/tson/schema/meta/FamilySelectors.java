package io.ltr8.tson.schema.meta;

import io.ltr8.unicode.Nfc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.function.Function;

/**
 * The fields a SEALED family dispatches on, resolved from the base's {@code discriminators} list -- one
 * derivation both encodings read, so a schema-load check and a read can never disagree about what selects a
 * member.
 *
 * <p><b>Why the names alone are not enough.</b> {@code record.discriminators} holds field names, and every
 * consumer needs each selector's declared
 * <em>type</em> as well: a pin is a bare token (§12.1) and only the type says which parser reads it. So the
 * names are resolved back to the base's own fields here.
 *
 * <p><b>Every base declares them itself, a family base included.</b> A record template's selector field is typed by
 * none of its parameters ({@code DefinitionResolver} refuses one that is), and a template's field types that name
 * no parameter are closed in place ({@code MetaRefs}), so a family base's selector types are the entries its
 * members' selectors name. A family with no declared member has a table like any other, with nothing in it to
 * select.
 */
public final class FamilySelectors {

    private FamilySelectors() {
    }

    /**
     * The selector fields {@code definition} dispatches on, keyed by their declared names, in the order {@code
     * discriminators} states -- which is the order their pins are compared as a tuple.
     */
    public static SequencedMap<String, RecordField> of(Top definition) {
        return switch (definition) {
            case RecordBody record -> declared(record.discriminators(), record.fields(), Optional::of);
            case RecordTemplateBody template -> declared(template.discriminators(), template.fields(),
                    field -> selector(field, template.parameterNames()));
            case null, default -> Collections.emptyNavigableMap();
        };
    }

    /**
     * Whether a value at a position typed by {@code definition} is placed by <b>reading its members</b>
     * rather than by its tag: ABSTRACT with at least one selector. The two facts are read together and
     * nowhere stated as one, so a base cannot claim a dispatch its body does not carry.
     */
    public static boolean dispatchesOnMembers(Top definition) {
        return extensionOf(definition).filter(RecordExtensionType.ABSTRACT::equals).isPresent()
                && !namesOf(definition).isEmpty();
    }

    /** The {@code extension} a base states, whichever kind of body holds it. */
    public static Optional<RecordExtensionType> extensionOf(Top definition) {
        return switch (definition) {
            case RecordBody record -> Optional.of(record.extension());
            // A family base is ABSTRACT by construction (§5.10), whatever its applications are.
            case RecordTemplateBody ignored -> Optional.of(RecordExtensionType.ABSTRACT);
            case null, default -> Optional.empty();
        };
    }

    /** The names a base states, whatever kind of base it is -- empty where it dispatches by tag or not at all. */
    public static List<String> namesOf(Top definition) {
        return switch (definition) {
            case RecordBody record -> record.discriminators();
            case RecordTemplateBody template -> template.discriminators();
            case null, default -> List.of();
        };
    }

    /**
     * A base's own fields, in {@code discriminators} order. A name that names no field yields nothing: the
     * linker reports that as the author's error, and a reader built over a schema that failed to load is not a
     * case this has to serve.
     */
    private static <F> SequencedMap<String, RecordField> declared(List<String> discriminators,
            Map<String, F> fields, Function<F, Optional<RecordField>> asSelector) {
        SequencedMap<String, RecordField> selectors = new LinkedHashMap<>();
        for (String name : discriminators) {
            field(fields, name).ifPresent(found -> asSelector.apply(found.getValue())
                    .ifPresent(selector -> selectors.put(found.getKey(), selector)));
        }
        return Collections.unmodifiableSequencedMap(selectors);
    }

    /**
     * A family base's selector as dispatch reads it: its type and annotations, required and unpinned. The
     * template's own field is pinned to a parameter (§5.7), which is what each member supplies and what the
     * dispatch compares, and is no value to read. A selector typed by a parameter yields nothing, the resolver
     * having refused it.
     */
    private static Optional<RecordField> selector(RecordTemplateField field, List<String> parameters) {
        if (field.parameterInType(parameters).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(new RecordField(field.type().orElseThrow(), false, false, FieldRole.FREE,
                Optional.empty(), field.annotations(), Optional.empty()));
    }

    /**
     * One field by name with its declared spelling, compared as §2.5 compares a field name -- NFC-normalised,
     * never by spelling.
     */
    private static <F> Optional<Map.Entry<String, F>> field(Map<String, F> fields, String name) {
        String wanted = Nfc.of(name);
        return fields.entrySet().stream().filter(f -> Nfc.of(f.getKey()).equals(wanted)).findFirst();
    }
}
