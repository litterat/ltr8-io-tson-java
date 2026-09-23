package io.ltr8.tson.schema.meta;

import io.ltr8.tson.base.unicode.Nfc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The fields a SEALED family dispatches on, resolved from the base's {@code discriminators} list -- one
 * derivation both encodings read, so a schema-load check and a read can never disagree about what selects a
 * member.
 *
 * <p><b>Why the names alone are not enough.</b> {@code record.discriminators} and {@code
 * template.discriminators} hold {@code field_name}s, and every consumer needs each selector's declared
 * <em>type</em> as well: a pin is a bare token (§12.1) and only the type says which parser reads it. So the
 * names are resolved back to fields here, and where they come from differs by the kind of base.
 *
 * <p><b>A closed base declares them itself.</b> The names index into its own {@code fields}, which is the
 * whole of it.
 *
 * <p><b>A template base has no resolved fields at all</b> -- its body is held text (§5.10) -- so each
 * selector's type is taken from any member's field of that name. That is exact rather than a best effort:
 * §5.7's identity diagonal forbids the base pinning what its members each pin differently, and the tightening
 * table governs a field's <em>state</em> and never its type, so every member carries the selector at the
 * base's own declared type. Measured on a family whose other argument narrows: the selector stays {@code
 * text} while the narrowing lands on the parameter-typed field beside it.
 *
 * <p>That is also what lets an encoding with no access to the schema pipeline's engine dispatch a sealed
 * template family: the selector types come from entries it already holds, never from parsing held text --
 * the parse §1.3 promises a resolved-output consumer never has to make.
 *
 * <p><b>An empty result is a real answer, not a failure.</b> A template family with no applications has no
 * members to take a type from, so it has no dispatch table -- and a value there is refused at read time for
 * having no member to be, which §3.3.4 makes right for a library base whose importers supply the family.
 */
public final class FamilySelectors {

    private FamilySelectors() {
    }

    /**
     * The selector fields {@code definition} dispatches on, in the order {@code discriminators} states --
     * which is the order their pins are compared as a tuple.
     *
     * @param entries the closure this base's members are looked up in, for a template base
     */
    public static List<RecordField> of(TypeDefinition definition, Map<String, TypeDefinition> entries) {
        return switch (definition.body()) {
            case RecordBody record -> declared(record);
            case TemplateBody held -> fromMembers(held.discriminators(), definition.subtypes(), entries);
            default -> List.of();
        };
    }

    /**
     * Whether a value at a position typed by {@code definition} is placed by <b>reading its members</b>
     * rather than by its tag: ABSTRACT with at least one selector. The two facts are read together and
     * nowhere stated as one, so a base cannot claim a dispatch its body does not carry.
     */
    public static boolean dispatchesOnMembers(TypeDefinition definition) {
        return extensionOf(definition).filter(RecordExtensionType.ABSTRACT::equals).isPresent()
                && !namesOf(definition).isEmpty();
    }

    /** The {@code extension} a base states, whichever kind of body holds it. */
    public static Optional<RecordExtensionType> extensionOf(TypeDefinition definition) {
        return switch (definition.body()) {
            case RecordBody record -> Optional.of(record.extension());
            case TemplateBody held -> held.extension();
            default -> Optional.empty();
        };
    }

    /** The names a base states, whatever kind of base it is -- empty where it dispatches by tag or not at all. */
    public static List<String> namesOf(TypeDefinition definition) {
        return switch (definition.body()) {
            case RecordBody record -> record.discriminators();
            case TemplateBody held -> held.discriminators();
            default -> List.of();
        };
    }

    /**
     * A closed base's own fields, in {@code discriminators} order. A name that names no field yields nothing:
     * the linker reports that as the author's error, and a reader built over a schema that failed to load is
     * not a case this has to serve.
     */
    private static List<RecordField> declared(RecordBody record) {
        List<RecordField> selectors = new ArrayList<>(record.discriminators().size());
        for (String name : record.discriminators()) {
            field(record, name).ifPresent(selectors::add);
        }
        return List.copyOf(selectors);
    }

    /**
     * A template base's selectors, each taken from the first member that declares a field of that name --
     * every member carrying it at the base's own type, for the reason the class note gives.
     *
     * <p>The field is handed back as the <em>base</em> would have declared it: required and unpinned, the pin
     * being what each member supplies and what the dispatch compares. A member's own copy is FIXED and would
     * say a selector is already decided, which is the opposite of what it is for.
     */
    private static List<RecordField> fromMembers(List<String> names, List<String> members,
                                                  Map<String, TypeDefinition> entries) {
        List<RecordField> selectors = new ArrayList<>(names.size());
        for (String name : names) {
            for (String member : members) {
                TypeDefinition definition = entries.get(member);
                if (definition == null || !(definition.body() instanceof RecordBody record)) {
                    continue;
                }
                Optional<RecordField> found = field(record, name);
                if (found.isPresent()) {
                    selectors.add(new RecordField(found.get().name(), found.get().type(), false, false,
                            FieldRole.FREE, Optional.empty(), found.get().annotations(), Optional.empty()));
                    break;
                }
            }
        }
        return List.copyOf(selectors);
    }

    /** One field by name, compared as §2.5 compares a field name -- NFC-normalised, never by spelling. */
    private static Optional<RecordField> field(RecordBody record, String name) {
        String wanted = Nfc.of(name);
        return record.fields().stream().filter(f -> Nfc.of(f.name()).equals(wanted)).findFirst();
    }
}
