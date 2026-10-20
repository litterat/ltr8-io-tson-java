package io.ltr8.tson.schema.meta;

import io.ltr8.unicode.Nfc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * The fields a SEALED family dispatches on, resolved from the base's {@code discriminators} list -- one
 * derivation both encodings read, so a schema-load check and a read can never disagree about what selects a
 * member.
 *
 * <p><b>Why the names alone are not enough.</b> {@code record.discriminators} holds field names, and every
 * consumer needs each selector's declared
 * <em>type</em> as well: a pin is a bare token (§12.1) and only the type says which parser reads it. So the
 * names are resolved back to fields here, and where they come from differs by the kind of base.
 *
 * <p><b>A closed base declares them itself.</b> The names index into its own {@code fields}, which is the
 * whole of it.
 *
 * <p><b>A record template base takes each selector's type from any member's field of that name</b>, a member
 * being closed where the base's own fields may still name its parameters. That is exact rather than a best effort:
 * §5.7's identity diagonal forbids the base pinning what its members each pin differently, and the tightening
 * table governs a field's <em>state</em> and never its type, so every member carries the selector at the
 * base's own declared type. Measured on a family whose other argument narrows: the selector stays {@code
 * text} while the narrowing lands on the parameter-typed field beside it.
 *
 * <p><b>An empty result is a real answer, not a failure.</b> A template family with no applications has no
 * members to take a type from, so it has no dispatch table -- and a value there is refused at read time for
 * having no member to be, which §3.3.4 makes right for a library base whose importers supply the family.
 */
public final class FamilySelectors {

    private FamilySelectors() {
    }

    /**
     * The selector fields {@code definition} dispatches on, keyed by their declared names, in the order {@code
     * discriminators} states -- which is the order their pins are compared as a tuple.
     *
     * @param name the base's own name, which a template base's members' chains name
     * @param entries the closure this base's members are looked up in, for a template base
     */
    public static SequencedMap<String, RecordField> of(String name, Top definition,
                                                       Map<String, ? extends Top> entries) {
        if (OpenEntry.isFamilyBase(definition)) {
            return fromMembers(namesOf(definition), Chained.subtypesOf(definition), entries);
        }
        return definition instanceof RecordBody record && !OpenEntry.isOpen(definition)
                ? declared(record) : Collections.emptyNavigableMap();
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
        if (OpenEntry.isOpen(definition)) {
            // A family base is ABSTRACT by construction (§5.10); any other open entry is no type at all.
            return OpenEntry.isFamilyBase(definition) ? Optional.of(RecordExtensionType.ABSTRACT) : Optional.empty();
        }
        return definition instanceof RecordBody record ? Optional.of(record.extension()) : Optional.empty();
    }

    /** The names a base states, whatever kind of base it is -- empty where it dispatches by tag or not at all. */
    public static List<String> namesOf(Top definition) {
        return switch (definition) {
            case RecordBody record -> record.discriminators();
            default -> List.of();
        };
    }

    /**
     * A closed base's own fields, in {@code discriminators} order. A name that names no field yields nothing:
     * the linker reports that as the author's error, and a reader built over a schema that failed to load is
     * not a case this has to serve.
     */
    private static SequencedMap<String, RecordField> declared(RecordBody record) {
        SequencedMap<String, RecordField> selectors = new LinkedHashMap<>();
        for (String name : record.discriminators()) {
            field(record, name).ifPresent(found -> selectors.put(found.getKey(), found.getValue()));
        }
        return Collections.unmodifiableSequencedMap(selectors);
    }

    /**
     * A template base's selectors, each taken from the first member that declares a field of that name --
     * every member carrying it at the base's own type, for the reason the class note gives.
     *
     * <p>The field is handed back as the <em>base</em> would have declared it: required and unpinned, the pin
     * being what each member supplies and what the dispatch compares. A member's own copy is FIXED and would
     * say a selector is already decided, which is the opposite of what it is for.
     */
    private static SequencedMap<String, RecordField> fromMembers(List<String> names, List<String> members,
                                                                 Map<String, ? extends Top> entries) {
        SequencedMap<String, RecordField> selectors = new LinkedHashMap<>();
        for (String name : names) {
            for (String member : members) {
                // A record template IS-A the base is no member, its applications being the members.
                if (!(entries.get(member) instanceof RecordBody record) || OpenEntry.isOpen(record)) {
                    continue;
                }
                Optional<Map.Entry<String, RecordField>> found = field(record, name);
                if (found.isPresent()) {
                    RecordField field = found.get().getValue();
                    selectors.put(found.get().getKey(), new RecordField(field.type(), false, false,
                            FieldRole.FREE, Optional.empty(), field.annotations(), Optional.empty()));
                    break;
                }
            }
        }
        return Collections.unmodifiableSequencedMap(selectors);
    }

    /**
     * One field by name with its declared spelling, compared as §2.5 compares a field name -- NFC-normalised,
     * never by spelling.
     */
    private static Optional<Map.Entry<String, RecordField>> field(RecordBody record, String name) {
        String wanted = Nfc.of(name);
        return record.fields().entrySet().stream().filter(f -> Nfc.of(f.getKey()).equals(wanted)).findFirst();
    }
}
