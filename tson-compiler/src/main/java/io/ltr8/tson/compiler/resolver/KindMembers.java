package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.KindBody;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The members of a {@code kind_type} instance: every entry of the governing schema's closure built with the
 * constructor its {@code of} names (SPEC-FEEDBACK.md #22), and the rule {@code of} itself obeys. Linking asks both,
 * and the {@code name_type} checks ask {@link #isMember} of the one entry a name resolved to.
 *
 * <p><b>Membership is one walk</b> over resolved entries, never over spellings. From an entry, alias hops first
 * ([TSON-SCHEMA] §8.3) reach the type the entry is; that type is no member if it is itself a constructor (IS-A
 * {@code top}), a reference, or a template over another constructor (a {@code template} body, which is no type).
 * Otherwise the constructor its value applies -- the tag resolved output writes, {@code !identifier_type}, {@code
 * !enum} -- is followed, through a constructor template's held body to the constructor it applies, until a
 * constructor is reached, and the entry is a member when that constructor is {@code of} or has {@code of} among its
 * supertypes. A record template is built with {@code record}: it is a type, the family base its members are IS-A
 * (§5.10), so a kind of {@code record} holds it. So does the kernel's {@code top}: a fresh abstract record built
 * with {@code record} and IS-A nothing, it is a type and not a constructor by the rule, and a member of every kind
 * whose {@code of} is {@code record} or above it -- {@link io.ltr8.tson.schema.meta.TypeKind#isConstructor}
 * answers for applicability, a different question, and is not consulted here.
 *
 * <p><b>A kind is a member of itself where the walk says so.</b> {@code type_kind} is built with {@code
 * kind_type}, which IS-A {@code type}, so it is one of the names it holds. Nothing loops: membership reads
 * constructors and never a kind's own members, and a value of a kind is a name, not a value read through the type
 * it names.
 *
 * <p><b>A constructor is read from the structure namespace first</b>, where §3.3.1 puts every constructor role, so a
 * schema's own entry under a constructor's name never captures a hop. A name the structure namespace does not hold
 * is read in the entry's own namespace -- which is where the meta-kernel's own constructors are while it links
 * itself, with no structure namespace loaded. A hop naming {@code of} itself reaches it whether or not a namespace
 * in reach declares it: the name is the constructor's.
 *
 * <p><b>A kind is not a bound.</b> {@code of} names a constructor of the structure namespace and the walk is
 * construction; {@code template_param.bound} names a type of the type-name namespace and walks IS-A. {@link
 * #ofRefusal} is what keeps the first from reading as the second.
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
     * @param structure the governing meta's namespace, where every constructor role resolves
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
        if (type == null || isConstructor(type)
                || type instanceof TemplateBody || type instanceof Reference) {
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
     * Why {@code kind}'s {@code of} cannot be a kind's, or empty where it can: it names a constructor of the
     * structure namespace, followed through its alias hops, that is {@code type} or IS-A it. A name that
     * namespace does not declare is refused, a type of the schema's own namespace among them; so is a
     * constructor above {@code type} or beside it, {@code data} and {@code template} included, which no value
     * has a type built with.
     */
    public static Optional<String> ofRefusal(KindBody kind, Function<String, Top> structure) {
        String of = kind.of();
        Optional<Found> constructor = reached(of, structure);
        if (constructor.isEmpty()) {
            return Optional.of("its 'of' names '" + of + "', which the structure namespace does not declare -- a "
                    + "kind names a constructor of the governing meta, as a constructor role does (§3.3.1), and "
                    + "never a type of this schema's own namespace");
        }
        if (constructor.get().name().equals(TYPE)
                || TypeChain.supertypesOf(constructor.get().definition()).contains(TYPE)) {
            return Optional.empty();
        }
        return Optional.of("its 'of' names '" + of + "', which is not 'type' or a constructor below it, so no "
                + "value has a type built with it and the kind could have no members -- name 'type', a base "
                + "kind, or a constructor that IS-A one");
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
