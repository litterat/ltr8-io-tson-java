package io.ltr8.tson.compiler.resolver;

import io.ltr8.unicode.IdentifierProfile;
import io.ltr8.unicode.Normalization;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.IdentifierType;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.KindBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.TextFamily;
import io.ltr8.tson.schema.meta.Top;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Where an entry's {@code name_type} resolves, and whether what it resolves to may stand there: an enum's, which its
 * members are values of ([TSON-SCHEMA] §7.4), and a record's, which its field names are values of ([TSON-SCHEMA] §5.2).
 * A value the entry's constructor wrote resolves where that constructor is declared, whose closure is in the schema's
 * -- so {@code !enum [A B]} names the kernel's {@code identifier}, and a record that says nothing the kernel's {@code
 * field_name}, names no schema reaching the kernel may redeclare; one an author wrote resolves in the schema's own
 * namespace first. Shared by the linker's enum and field name checks and by materialisation, which checks a template's
 * value argument of an enum type before linking has run.
 *
 * <p><b>What may stand there is the slot's kind's to say</b> ([TSON-SCHEMA] §4.2). The kernel types both slots
 * {@code identifier_kind}, the names of the types built with {@code identifier_type}, and {@link #admits} reads that
 * kind off the constructor the entry is built with -- the {@code name_type} field's own type -- and asks {@link
 * KindMembers} whether the referent is a member. The resolution above is the store and the kind the judgment: the
 * referent is found first, and the kind refuses the one found. Where the slot's kind is out of reach -- no governing
 * meta loaded, or a meta layer that reaches the kernel's kinds through its own {@code !!meta} without importing them
 * -- the kernel's {@code identifier_kind} is what the slot is typed by in every meta this library loads, and its
 * {@code of} is used.
 */
public final class NameType {

    private static final String NAME_TYPE = "name_type";

    /** The {@code of} of the kernel's {@code identifier_kind}, for a slot whose own kind is out of reach. */
    private static final String IDENTIFIER_TYPE = "identifier_type";

    private NameType() {
    }

    /** The name type's value, and the namespace its own names resolve in. */
    public record Resolved(Top definition, Function<String, Top> lookup) {
    }

    /**
     * {@code entry}'s name type, given the constructor its value applies (an enum's decides whether its {@code
     * name_type} is the constructor's pin), and two lookups, the schema's own namespace and the one holding the
     * constructor -- the same namespace, from the linker. Empty for a body
     * that is neither an enum nor a record, and for a {@code name_type} that resolves in neither namespace.
     */
    public static Optional<Resolved> of(Top entry, Optional<String> constructor, Function<String, Top> local,
                                        Function<String, Top> structure) {
        return switch (entry) {
            case EnumBody body -> ofEnum(constructor, body, local, structure);
            case RecordBody body -> ofRecord(body, local, structure);
            default -> Optional.empty();
        };
    }

    /**
     * The profile {@code entry}'s names are judged under by [TSON-DATA] §8.2's per-name rules: its name type's, whose
     * added characters -- a {@code start_add}, a {@code continue_add}, a {@code medial} -- are the profile's own.
     * Empty where the type resolves to nothing or is not a member of the slot's kind, each refused on its own.
     */
    public static Optional<IdentifierProfile> profile(Top entry, Optional<String> constructor,
                                                      Function<String, Top> local, Function<String, Top> structure) {
        return of(entry, constructor, local, structure)
                .filter(type -> admits(entry, constructor, type, local, structure))
                .map(type -> type.definition() instanceof IdentifierType identifiers ? identifiers.profile() : null);
    }

    /**
     * Whether {@code type}, {@code entry}'s resolved name type, is a member of the kind its {@code name_type} slot
     * is typed by -- read off {@code constructor}, the one {@code entry}'s value applies: {@code identifier_kind}
     * for both of the kernel's slots. A referent that resolved to nothing is no member. The referent's own
     * constructor is its body's ({@link TsonSchema#constructorOf}), the resolved value being all a name type is
     * resolved to here.
     */
    public static boolean admits(Top entry, Optional<String> constructor, Resolved type, Function<String, Top> local,
                                 Function<String, Top> structure) {
        if (type.definition() == null) {
            return false;
        }
        String of = slotKind(entry, constructor, local, structure).map(KindBody::of).orElse(IDENTIFIER_TYPE);
        return KindMembers.isMember(type.definition(), TsonSchema.constructorOf(type.definition()), of,
                type.lookup(), structure);
    }

    /**
     * The kind {@code entry}'s {@code name_type} slot is typed by: the {@code name_type} field of the constructor
     * {@code entry}'s value applies -- through a constructor template's held body to the constructor it applies
     * -- read in {@code structure} first, then {@code local}; the linker hands both the schema's one namespace.
     * Empty where no such constructor or kind is in reach.
     */
    private static Optional<KindBody> slotKind(Top entry, Optional<String> constructor, Function<String, Top> local,
                                               Function<String, Top> structure) {
        Function<String, Top> constructors = fallingBackTo(structure, local);
        Optional<String> head = constructor.or(() -> Optional.of(TsonSchema.constructorOf(entry)));
        Set<String> seen = new HashSet<>();
        while (head.isPresent() && seen.add(head.get())) {
            Top found = constructors.apply(ReferenceChain.terminal(head.get(), constructors));
            if (found instanceof TemplateBody held) {
                head = HeldBody.of(held).application().typeRef();
                continue;
            }
            return slotKindOf(found, constructors);
        }
        return Optional.empty();
    }

    private static Optional<KindBody> slotKindOf(Top constructor, Function<String, Top> constructors) {
        if (!(constructor instanceof RecordBody record)) {
            return Optional.empty();
        }
        return Optional.ofNullable(record.fields().get(NAME_TYPE))
                .map(field -> constructors.apply(ReferenceChain.terminal(field.type().name(), constructors)))
                .filter(KindBody.class::isInstance)
                .map(KindBody.class::cast);
    }

    /**
     * The form {@code enumeration} matches its members in: its name type's {@code normalization}
     * ([TSON-SCHEMA] §5.5). {@code NONE} for a body that is not an enum, a type that resolves to nothing, or one
     * that is no identifier family -- each refused or reported on its own.
     */
    public static Normalization form(Top enumeration, Optional<String> constructor, Function<String, Top> local,
                                     Function<String, Top> structure) {
        if (!(enumeration instanceof EnumBody)) {
            return Normalization.NONE;
        }
        return of(enumeration, constructor, local, structure)
                .map(type -> type.definition() instanceof TextFamily family
                        ? family.normalization()
                        : Normalization.NONE)
                .orElse(Normalization.NONE);
    }

    /**
     * Whether {@code type} is the kernel's {@code identifier} -- an enum's default, and through {@code field_name} a
     * record's -- whose names a refusal calls identifiers, where it names any other type, the author's own.
     */
    public static boolean isIdentifier(Resolved type, Function<String, Top> structure) {
        return type.definition() != null
                && type.definition() == structure.apply(ReferenceChain.terminal(EnumBody.IDENTIFIER, structure));
    }

    /**
     * Why {@code type}'s {@code normalization} cannot judge names, or empty when it can. Names are compared in a
     * form that implies NFC: under one that does not, two canonically equivalent spellings are two names that
     * render alike, the confusion §8.2's rules exist to refuse, and no reader could match a document's name to a
     * declared one without choosing a form the type never stated. {@code NONE} implies none; {@code ASCII_CASEFOLD}
     * implies NFC only of ASCII text, so it names a type whose every character is ASCII -- an {@code ASCII} or
     * empty base, and additions within ASCII.
     */
    public static Optional<String> formRefusal(IdentifierType type) {
        return switch (type.normalization()) {
            case NONE -> Optional.of("its normalization is NONE, under which two canonically equivalent spellings "
                    + "are two names; a name type's form implies NFC");
            case ASCII_CASEFOLD -> asciiOnly(type) ? Optional.empty() : Optional.of("its normalization is "
                    + "ASCII_CASEFOLD, which leaves text beyond ASCII unnormalized, and its profile admits such text; "
                    + "a name type's form implies NFC -- use NFKC_CASEFOLD, or admit only ASCII "
                    + "('start: ASCII  continue: ASCII')");
            case NFC, NFKC, NFKC_CASEFOLD -> Optional.empty();
        };
    }

    private static boolean asciiOnly(IdentifierType type) {
        return asciiBase(type.start()) && asciiBase(type.continueBase())
                && Stream.of(type.startAdd(), type.continueAdd(), type.medial())
                        .flatMap(Optional::stream).allMatch(text -> text.chars().allMatch(c -> c < 0x80));
    }

    private static boolean asciiBase(IdentifierProfile.Base base) {
        return base == IdentifierProfile.Base.ASCII || base == IdentifierProfile.Base.NONE;
    }

    private static Optional<Resolved> ofEnum(Optional<String> constructor, EnumBody body,
                                             Function<String, Top> local,
                                             Function<String, Top> structure) {
        if (pinned(constructor, body, fallingBackTo(local, structure)) && structure.apply(body.nameType()) != null) {
            return Optional.of(new Resolved(structure.apply(ReferenceChain.terminal(body.nameType(), structure)),
                    structure));
        }
        if (local.apply(body.nameType()) != null) {
            return Optional.of(new Resolved(local.apply(ReferenceChain.terminal(body.nameType(), local)),
                    fallingBackTo(local, structure)));
        }
        return Optional.empty();
    }

    /**
     * A name type the record states was written by its author, so it resolves in the schema's own namespace first,
     * as an enum's does; the absent default is the kernel's {@code field_name}, resolved in the structure first.
     */
    private static Optional<Resolved> ofRecord(RecordBody body, Function<String, Top> local,
                                               Function<String, Top> structure) {
        String name = body.nameTypeName();
        Function<String, Top> first = body.nameType().isEmpty() ? structure : local;
        Function<String, Top> second = first == structure ? local : structure;
        for (Function<String, Top> namespace : List.of(first, second)) {
            if (namespace.apply(name) != null) {
                Function<String, Top> other = namespace == first ? second : first;
                return Optional.of(new Resolved(namespace.apply(ReferenceChain.terminal(name, namespace)),
                        fallingBackTo(namespace, other)));
            }
        }
        return Optional.empty();
    }

    /**
     * Whether {@code name_type} is the value the enum's {@code constructor} pins -- through a template's held body
     * to the constructor it applies, where the name given is a template's.
     */
    private static boolean pinned(Optional<String> constructor, EnumBody body, Function<String, Top> lookup) {
        Optional<String> head = constructor;
        Set<String> seen = new HashSet<>();
        while (head.isPresent() && seen.add(head.get())) {
            Top applied = lookup.apply(head.get());
            if (OpenEntry.isOpen(applied)) {
                head = HeldBody.of(applied).application().typeRef();
                continue;
            }
            return applied instanceof RecordBody record
                    && Optional.ofNullable(record.fields().get(NAME_TYPE))
                            .filter(field -> field.role() == FieldRole.FIXED)
                            .flatMap(RecordField::value)
                            .filter(value -> value.text().equals(body.nameType())).isPresent();
        }
        return false;
    }

    private static Function<String, Top> fallingBackTo(Function<String, Top> primary,
                                                                  Function<String, Top> fallback) {
        return name -> {
            Top found = primary.apply(name);
            return found != null ? found : fallback.apply(name);
        };
    }
}
