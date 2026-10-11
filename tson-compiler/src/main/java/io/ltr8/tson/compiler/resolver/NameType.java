package io.ltr8.tson.compiler.resolver;

import io.ltr8.unicode.IdentifierProfile;
import io.ltr8.unicode.Normalization;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.IdentifierType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.TextFamily;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Where an entry's {@code name_type} resolves: an enum's, which its members are values of ([TSON-SCHEMA] §7.4), and
 * a record's, which its field names are values of (SPEC-FEEDBACK.md #10). Either names an identifier family, so the
 * names are judged as names, under its profile. A value the entry's constructor wrote
 * resolves where that constructor is declared, the governing meta's namespace -- so {@code !enum [A B]} names the
 * kernel's {@code identifier}, and a record that says nothing the kernel's {@code field_name}, whatever the schema
 * declares or imports; one an author wrote resolves in the schema's own namespace first. Shared by the linker's
 * enum and field name checks and by materialisation, which checks a template's value argument of an enum type
 * before linking has run.
 */
public final class NameType {

    private static final String NAME_TYPE = "name_type";

    private NameType() {
    }

    /** The name type's definition, and the namespace its own names resolve in. */
    public record Resolved(TypeDefinition definition, Function<String, TypeDefinition> lookup) {
    }

    /**
     * {@code entry}'s name type, given the schema's own namespace and the governing meta's. Empty for a body that is
     * neither an enum nor a record, and for a {@code name_type} that resolves in neither namespace.
     */
    public static Optional<Resolved> of(TypeDefinition entry, Function<String, TypeDefinition> local,
                                        Function<String, TypeDefinition> structure) {
        return switch (entry.body()) {
            case EnumBody body -> ofEnum(entry, body, local, structure);
            case RecordBody body -> ofRecord(body, local, structure);
            default -> Optional.empty();
        };
    }

    /**
     * The profile {@code entry}'s names are judged under by [TSON-DATA] §8.2's per-name rules: its name type's, whose
     * added characters -- a {@code start_add}, a {@code continue_add}, a {@code medial} -- are the profile's own.
     * Empty where the type resolves to nothing or is no identifier family, each refused on its own.
     */
    public static Optional<IdentifierProfile> profile(TypeDefinition entry, Function<String, TypeDefinition> local,
                                                      Function<String, TypeDefinition> structure) {
        return of(entry, local, structure)
                .map(type -> type.definition() == null ? null : type.definition().body())
                .filter(IdentifierType.class::isInstance)
                .map(body -> ((IdentifierType) body).profile());
    }

    /**
     * The form {@code enumeration} matches its members in: its name type's {@code normalization}
     * ([TSON-SCHEMA] §5.5). {@code NONE} for a body that is not an enum, a type that resolves to nothing, or one
     * that is no identifier family -- each refused or reported on its own.
     */
    public static Normalization form(TypeDefinition enumeration, Function<String, TypeDefinition> local,
                                     Function<String, TypeDefinition> structure) {
        if (!(enumeration.body() instanceof EnumBody)) {
            return Normalization.NONE;
        }
        return of(enumeration, local, structure)
                .map(type -> type.definition() != null && type.definition().body() instanceof TextFamily family
                        ? family.normalization()
                        : Normalization.NONE)
                .orElse(Normalization.NONE);
    }

    /**
     * Whether {@code type} is the kernel's {@code identifier} -- an enum's default, and through {@code field_name} a
     * record's -- whose names a refusal calls identifiers, where it names any other type, the author's own.
     */
    public static boolean isIdentifier(Resolved type, Function<String, TypeDefinition> structure) {
        return type.definition() != null
                && type.definition() == structure.apply(ReferenceChain.terminal(EnumBody.IDENTIFIER, structure));
    }

    /**
     * Why {@code type}'s {@code normalization} cannot judge names, or empty when it can. Names are compared in a
     * form that implies NFC: under one that does not, two canonically equivalent spellings are two names that
     * render alike, the confusion §8.2's rules exist to refuse, and no reader could match a document's name to a
     * declared one without choosing a form the type never stated. {@code NONE} implies none; {@code ASCII_CASEFOLD}
     * implies NFC only of ASCII text, so it names a type whose every character is ASCII.
     */
    public static Optional<String> formRefusal(IdentifierType type) {
        return switch (type.normalization()) {
            case NONE -> Optional.of("its normalization is NONE, under which two canonically equivalent spellings "
                    + "are two names; a name type's form implies NFC");
            case ASCII_CASEFOLD -> asciiOnly(type) ? Optional.empty() : Optional.of("its normalization is "
                    + "ASCII_CASEFOLD, which leaves text beyond ASCII unnormalized, and its profile admits such text; "
                    + "a name type's form implies NFC -- use NFKC_CASEFOLD, or admit only ASCII");
            case NFC, NFKC, NFKC_CASEFOLD -> Optional.empty();
        };
    }

    private static boolean asciiOnly(IdentifierType type) {
        return type.start() == IdentifierProfile.Base.NONE && type.continueBase() == IdentifierProfile.Base.NONE
                && Stream.of(type.startAdd(), type.continueAdd(), type.medial())
                        .flatMap(Optional::stream).allMatch(text -> text.chars().allMatch(c -> c < 0x80));
    }

    private static Optional<Resolved> ofEnum(TypeDefinition enumeration, EnumBody body,
                                             Function<String, TypeDefinition> local,
                                             Function<String, TypeDefinition> structure) {
        if (pinned(enumeration, body, fallingBackTo(local, structure)) && structure.apply(body.nameType()) != null) {
            return Optional.of(new Resolved(structure.apply(ReferenceChain.terminal(body.nameType(), structure)),
                    structure));
        }
        if (local.apply(body.nameType()) != null) {
            return Optional.of(new Resolved(local.apply(ReferenceChain.terminal(body.nameType(), local)),
                    fallingBackTo(local, structure)));
        }
        return Optional.empty();
    }

    /** The kernel's default was written by the {@code record} constructor, so it resolves in the structure first. */
    private static Optional<Resolved> ofRecord(RecordBody body, Function<String, TypeDefinition> local,
                                               Function<String, TypeDefinition> structure) {
        String name = body.nameType();
        Function<String, TypeDefinition> first = name.equals(RecordBody.FIELD_NAME) ? structure : local;
        Function<String, TypeDefinition> second = first == structure ? local : structure;
        for (Function<String, TypeDefinition> namespace : List.of(first, second)) {
            if (namespace.apply(name) != null) {
                Function<String, TypeDefinition> other = namespace == first ? second : first;
                return Optional.of(new Resolved(namespace.apply(ReferenceChain.terminal(name, namespace)),
                        fallingBackTo(namespace, other)));
            }
        }
        return Optional.empty();
    }

    /**
     * Whether {@code name_type} is the value {@code enumeration}'s constructor pins -- found through its
     * {@code source}, and through a template's held body to the constructor it applies, since an entry instantiating
     * {@code names => <M> !enum [a b M]} records the template as its source and not {@code enum}.
     */
    private static boolean pinned(TypeDefinition enumeration, EnumBody body, Function<String, TypeDefinition> lookup) {
        Optional<String> head = enumeration.source().map(source -> source.name());
        Set<String> seen = new HashSet<>();
        while (head.isPresent() && seen.add(head.get())) {
            TypeDefinition constructor = lookup.apply(head.get());
            if (constructor != null && constructor.body() instanceof TemplateBody held) {
                head = HeldBody.of(held).application().typeRef();
                continue;
            }
            return constructor != null && constructor.body() instanceof RecordBody record && record.fields().stream()
                    .filter(field -> field.name().equals(NAME_TYPE) && field.role() == FieldRole.FIXED)
                    .flatMap(field -> field.value().stream())
                    .anyMatch(value -> value.text().equals(body.nameType()));
        }
        return false;
    }

    private static Function<String, TypeDefinition> fallingBackTo(Function<String, TypeDefinition> primary,
                                                                  Function<String, TypeDefinition> fallback) {
        return name -> {
            TypeDefinition found = primary.apply(name);
            return found != null ? found : fallback.apply(name);
        };
    }
}
