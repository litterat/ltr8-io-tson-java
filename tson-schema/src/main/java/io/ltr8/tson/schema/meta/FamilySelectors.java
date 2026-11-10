package io.ltr8.tson.schema.meta;

import io.ltr8.unicode.Nfc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * The fields a member-dispatched family dispatches on, resolved from the base's {@code discriminators} list -- one
 * derivation both encodings read, so a schema-load check and a read can never disagree about what selects a
 * member.
 *
 * <p><b>Why the names alone are not enough.</b> {@code record.discriminators} holds field names, and every
 * consumer needs each selector's declared
 * <em>type</em> as well: a pin is a bare token (§12.1) and only the type says which parser reads it. So the
 * names are resolved back to the base's own fields here.
 *
 * <p><b>Every base is a record and declares them itself.</b> A template is no type (§5.10), so no position is
 * typed by one: a family whose base is an abstract template's application is based on the closed record that
 * application denotes, which carries the template's selectors. A family with no declared member has a table like
 * any other, with nothing in it to select.
 */
public final class FamilySelectors {

    private FamilySelectors() {
    }

    /**
     * The selector fields {@code definition} dispatches on, keyed by their declared names, in the order {@code
     * discriminators} states -- which is the order their pins are compared as a tuple.
     */
    public static SequencedMap<String, RecordField> of(Top definition) {
        return definition instanceof RecordBody record
                ? declared(record.discriminators(), record.fields())
                : Collections.emptyNavigableMap();
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

    /** The {@code extension} a base states -- empty for anything but a record. */
    public static Optional<RecordExtensionType> extensionOf(Top definition) {
        return definition instanceof RecordBody record ? Optional.of(record.extension()) : Optional.empty();
    }

    /** The names a base states -- empty where it dispatches by tag or not at all, or is no record. */
    public static List<String> namesOf(Top definition) {
        return definition instanceof RecordBody record ? record.discriminators() : List.of();
    }

    /**
     * A base's own fields, in {@code discriminators} order. A name that names no field yields nothing: the
     * linker reports that as the author's error, and a reader built over a schema that failed to load is not a
     * case this has to serve.
     */
    private static SequencedMap<String, RecordField> declared(List<String> discriminators,
                                                              Map<String, RecordField> fields) {
        SequencedMap<String, RecordField> selectors = new LinkedHashMap<>();
        for (String name : discriminators) {
            field(fields, name).ifPresent(found -> selectors.put(found.getKey(), found.getValue()));
        }
        return Collections.unmodifiableSequencedMap(selectors);
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
