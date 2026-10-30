package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.KindBody;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.ValueEntry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The members of a {@code kind_type} instance: every entry of the governing schema's closure built with the
 * constructor its {@code of} names ([TSON-SCHEMA] §4.2), and the rule {@code of} itself obeys. Linking asks both,
 * and the {@code name_type} checks ask {@link #isMember} of the one entry a name resolved to.
 *
 * <p><b>Membership is one walk</b> over resolved entries, never over spellings. From an entry, alias hops first
 * ([TSON-SCHEMA] §8.3) reach the type the entry is; that type is no member if it is a type constructor, a
 * reference, a template over another constructor (a {@code template} body, which is no type), or a value entry,
 * which is no type at all. A constructor IS-A {@code top}, and is excluded because its instances are types. For
 * every other type the constructor its value applies -- the tag resolved output writes, {@code !identifier_type},
 * {@code !enum}, {@code !namespace} -- is followed, through a constructor template's held body to the constructor
 * it applies, until a constructor is reached, and the entry is a member when that constructor is {@code of} or has
 * {@code of} among its supertypes. A value entry's tag names an ordinary type, which no walk reaches a constructor
 * through, so it is a member of no kind. A record template is built with
 * {@code record}: it is a type, the family base its members are IS-A (§5.10), so a kind of {@code record} holds
 * it. So does the kernel's {@code top}: a fresh abstract record built with {@code record} and IS-A nothing, it is
 * a type and not a constructor by the rule, and a member of every kind whose {@code of} is {@code record} or above
 * it -- {@link io.ltr8.tson.schema.meta.EntryKind#isConstructor} answers for applicability, a different question,
 * and is not consulted here.
 *
 * <p><b>A kind is a member of itself where the walk says so.</b> {@code type_kind} is built with {@code
 * kind_type}, which IS-A {@code type}, so it is one of the names it holds. Nothing loops: membership reads
 * constructors and never a kind's own members, and a value of a kind is a name, not a value read through the type
 * it names.
 *
 * <p><b>A constructor is read from the {@code structure} lookup first, then the entry's own namespace.</b> The linker
 * hands both the schema's one namespace (SPEC-FEEDBACK.md #7), where a constructor and a type share one store and a
 * name denotes one entry; the second lookup serves a caller holding a narrower first one. A hop naming {@code of}
 * itself reaches it whether or not a namespace in reach declares it: the name is the constructor's.
 *
 * <p><b>A kind is not a bound.</b> {@code of} names a constructor and the walk is construction; {@code
 * template_param.bound} names a type and walks IS-A. {@link #ofRefusal} is what keeps the first from reading as the
 * second.
 */
public final class KindMembers {

    private static final String TOP = "top";
    private static final String TYPE = "type";

    private KindMembers() {
    }

    /**
     * Every entry of {@code merged} that is a member of {@code kind}, in the map's own order -- {@link
     * KindBody#linked} sorts them.
     *
     * @param constructorOf the constructor an entry of {@code merged} applies, from its recorded facts
     * @param structure where a constructor is looked up first; the linker hands it the schema's one namespace
     */
    public static List<String> of(KindBody kind, Map<String, Top> merged,
                                  Function<String, Optional<String>> constructorOf, Function<String, Top> structure) {
        List<String> members = new ArrayList<>();
        for (String name : merged.keySet()) {
            if (isMember(name, kind.of(), merged::get, constructorOf, structure)) {
                members.add(name);
            }
        }
        return members;
    }

    /**
     * Whether the entry {@code name} names in {@code local} is a member of the kind of {@code of}.
     *
     * @param constructorOf the constructor an entry of {@code local} applies, from its recorded facts
     */
    public static boolean isMember(String name, String of, Function<String, Top> local,
                                   Function<String, Optional<String>> constructorOf, Function<String, Top> structure) {
        String terminal = ReferenceChain.terminal(name, local);
        Top type = local.apply(terminal);
        return type != null && isMember(type, constructorOf.apply(terminal)
                .orElseGet(() -> TsonSchema.constructorOf(type)), of, local, structure);
    }

    /**
     * Whether {@code type} -- an entry already at the end of its alias hops, found in {@code local}, whose value
     * applies the constructor {@code head} -- is a member of the kind of {@code of}.
     */
    public static boolean isMember(Top type, String head, String of, Function<String, Top> local,
                                   Function<String, Top> structure) {
        Function<String, Top> constructors = structureFirst(structure, local);
        if (type == null || type instanceof TemplateBody || type instanceof Reference || type instanceof ValueEntry
                || isConstructor(type)) {
            return false;
        }
        String constructorOf = ReferenceChain.terminal(of, constructors);
        Set<String> walked = new HashSet<>();
        String current = head;
        while (walked.add(current)) {
            Optional<Found> next = reached(current, constructors);
            if (next.isEmpty()) {
                return current.equals(constructorOf);
            }
            if (next.get().definition() instanceof TemplateBody held) {
                // A constructor template (`kind => <T> !kind_type { of: T }`): built with what its body applies.
                Optional<String> applied = HeldBody.of(held).application().typeRef();
                if (applied.isEmpty()) {
                    return false;
                }
                current = applied.get();
                continue;
            }
            return isConstructor(next.get().definition()) && reaches(next.get(), constructorOf);
        }
        return false;
    }

    /**
     * Why {@code kind}'s {@code of} cannot be a kind's, or empty where it can: it names a constructor in {@code
     * structure}, followed through its alias hops, that is {@code type} or IS-A it. A name {@code structure} does not
     * declare is refused; so is a type that is no constructor ({@code kind<api>}, whose values are named by {@code
     * ref<api>}), and a constructor above {@code type} or beside it, {@code template} and {@code reference} included,
     * which no value has a type built with.
     */
    public static Optional<String> ofRefusal(KindBody kind, Function<String, Top> structure) {
        String of = kind.of();
        Optional<Found> constructor = reached(of, structure);
        if (constructor.isEmpty()) {
            return Optional.of("its 'of' names '" + of + "', which this schema's namespace does not declare -- a "
                    + "kind names a constructor");
        }
        List<String> supertypes = TypeChain.supertypesOf(constructor.get().definition());
        if (constructor.get().name().equals(TYPE) || supertypes.contains(TYPE)) {
            return Optional.empty();
        }
        if (!supertypes.contains(TOP)) {
            return Optional.of("its 'of' names '" + of + "', which is a type and no constructor, so no type is built "
                    + "with it and the kind could have no members -- a kind names the types a constructor builds; "
                    + "the values of the type '" + of + "' are named by a reference, ref<" + of + ">");
        }
        return Optional.of("its 'of' names '" + of + "', which is not 'type' or a constructor below it, so no "
                + "value has a type built with it and the kind could have no members -- name 'type', a base kind, "
                + "or a constructor that IS-A one");
    }

    private static boolean reaches(Found constructor, String of) {
        return constructor.name().equals(of) || TypeChain.supertypesOf(constructor.definition()).contains(of);
    }

    /** The entry at the end of {@code name}'s §8.3 chain in {@code entries}, or empty where the walk reaches none. */
    private static Optional<Found> reached(String name, Function<String, Top> entries) {
        String terminal = ReferenceChain.terminal(name, entries);
        Top definition = entries.apply(terminal);
        return definition == null || definition instanceof Reference ? Optional.empty()
                : Optional.of(new Found(terminal, definition));
    }

    private static Function<String, Top> structureFirst(Function<String, Top> structure, Function<String, Top> local) {
        return name -> {
            Top found = structure.apply(name);
            return found != null ? found : local.apply(name);
        };
    }

    /** An entry reached by a walk, under the name it is declared by. */
    private record Found(String name, Top definition) {
    }

    private static boolean isConstructor(Top definition) {
        return TypeChain.supertypesOf(definition).contains(TOP);
    }
}
