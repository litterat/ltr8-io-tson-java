package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ReferenceBody;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.OpenEntry;
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
 * The one predicate a name slot applies ([TSON-SCHEMA] §4.2, §5.12): a {@code reference} over {@code of} admits a
 * name exactly when <b>the type of the named entry's value, past its alias hops, IS-A {@code of}</b>. An entry's value
 * has a type, its tag: {@code order => { … }} holds a value of {@code record}, {@code int32 => !integer ^ { … }} a
 * value of {@code integer_type}, {@code orders => !api { … }} a value of {@code api}. A field's type position
 * ({@code type_kind}, the reference over {@code type}), {@code kind<record>} and {@code ref<method>} all ask it.
 *
 * <p><b>What {@code of} names decides which names a slot holds</b> ({@link #namesTypes}): a constructor, {@code top}
 * or IS-A it, has types as its values, so the slot holds type names and the linker computes them ({@link #of}); any
 * other type has values as its values, and the slot holds a value's qualified name, which the linker judges where it
 * is written ({@code ValuesByName}). The partition is by {@code of}, never by the spelling: {@code kind<order>} and
 * {@code ref<order>} are one reference.
 *
 * <p><b>Membership of a type name is one walk</b> over resolved entries, never over spellings. From an entry, alias
 * hops first ([TSON-SCHEMA] §8.3) reach the entry the name means; an alias that stops at an application, a template
 * over another constructor (a {@code template} body, which is no type) and a value entry are no type's name. For every
 * other entry the type of its value -- the tag resolved output writes, {@code !record}, {@code !identifier_type},
 * {@code !namespace} -- is followed, through a constructor template's held body to the constructor it applies, until a
 * constructor is reached, and the entry is a member when that constructor is {@code of} or has {@code of} among its
 * supertypes. A record template is built with {@code record}, so a reference over {@code record} holds it. So does
 * the kernel's {@code top}, a fresh abstract record IS-A nothing. So does a constructor whose declaration is a record,
 * {@code uuid_type => atom & { … }}: its value is a record whose instances are declarations, and nothing in the
 * predicate sets it apart -- {@link io.ltr8.tson.schema.meta.EntryKind#isConstructor} answers for applicability, a
 * different question, and is not consulted here.
 *
 * <p><b>A reference is a member of itself where the walk says so.</b> {@code type_kind} is built with {@code
 * reference}, which IS-A {@code type}, so it is one of the names it holds. Nothing loops: membership reads
 * constructors and never a reference's own members, and a value of a reference is a name, not a value read through
 * the type it names.
 *
 * <p><b>A constructor is read from the {@code structure} lookup first, then the entry's own namespace.</b> The linker
 * hands both the schema's one namespace (SPEC-FEEDBACK.md #7), where a constructor and a type share one store and a
 * name denotes one entry; the second lookup serves a caller holding a narrower first one. A hop naming {@code of}
 * itself reaches it whether or not a namespace in reach declares it: the name is the constructor's.
 *
 * <p><b>A reference is not a bound.</b> {@code of} is the type of the named entry's value, and the walk is
 * construction; {@code template_param.bound} names a type the argument itself IS-A.
 */
public final class KindMembers {

    private static final String TOP = "top";

    private KindMembers() {
    }

    /**
     * Every entry of {@code merged} whose name {@code reference}, one that names types, admits, in the map's own
     * order -- {@link ReferenceBody#linked} sorts them.
     *
     * @param constructorOf the constructor an entry of {@code merged} applies, from its recorded facts
     * @param structure where a constructor is looked up first; the linker hands it the schema's one namespace
     */
    public static List<String> of(ReferenceBody reference, Map<String, Top> merged,
                                  Function<String, Optional<String>> constructorOf, Function<String, Top> structure) {
        List<String> members = new ArrayList<>();
        for (String name : merged.keySet()) {
            if (isMember(name, reference.of(), merged::get, constructorOf, structure)) {
                members.add(name);
            }
        }
        return members;
    }

    /**
     * Whether the entry {@code name} names in {@code local} is a member of the reference over {@code of}, a
     * constructor.
     *
     * @param constructorOf the constructor an entry of {@code local} applies, from its recorded facts
     */
    public static boolean isMember(String name, String of, Function<String, Top> local,
                                   Function<String, Optional<String>> constructorOf, Function<String, Top> structure) {
        String terminal = AliasChain.terminal(name, local);
        Top type = local.apply(terminal);
        return type != null && isMember(type, constructorOf.apply(terminal)
                .orElseGet(() -> TsonSchema.constructorOf(type)), of, local, structure);
    }

    /**
     * Whether {@code type} -- an entry already at the end of its alias hops, found in {@code local}, whose value
     * applies the constructor {@code head} -- is a member of the reference over {@code of}, a constructor.
     */
    public static boolean isMember(Top type, String head, String of, Function<String, Top> local,
                                   Function<String, Top> structure) {
        Function<String, Top> constructors = structureFirst(structure, local);
        if (type == null || type instanceof TemplateBody || type instanceof Alias || type instanceof ValueEntry) {
            return false;
        }
        String constructorOf = AliasChain.terminal(of, constructors);
        Set<String> walked = new HashSet<>();
        String current = head;
        while (walked.add(current)) {
            Optional<Found> next = reached(current, constructors);
            if (next.isEmpty()) {
                return current.equals(constructorOf);
            }
            if (next.get().definition() instanceof TemplateBody held) {
                // A constructor template (`kind => <T> !reference { of: T }`): built with what its body applies.
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
     * Whether a reference over {@code of} names types: {@code of}, past its alias hops in {@code structure}, is {@code
     * top} or a constructor IS-A it. False where {@code of} reaches nothing, as a name of an entry not yet ready does
     * during a partial link: a qualified name's reading is the permissive one, and the final link judges again.
     */
    public static boolean namesTypes(String of, Function<String, Top> structure) {
        return reached(of, structure).map(found -> found.name().equals(TOP) || isConstructor(found.definition()))
                .orElse(false);
    }

    /**
     * Why {@code reference}'s {@code of} cannot be one, or empty where it can: any type or constructor of the writer's
     * namespace may be. Refused are a name {@code structure} does not declare (an empty answer from this method's
     * caller's resolution, reported as an unresolved reference), an open entry, which denotes no type until it is
     * applied (§5.10), and a value entry, which is a value and no type.
     */
    public static Optional<String> ofRefusal(ReferenceBody reference, Function<String, Top> structure) {
        String of = reference.of();
        Optional<Found> found = reached(of, structure);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Top definition = found.get().definition();
        if (definition instanceof ValueEntry) {
            return Optional.of("its 'of' names '" + of + "', which is a value and no type -- a reference admits the "
                    + "names of entries whose values are of a type, and nothing has a value of a value");
        }
        if (OpenEntry.isOpen(definition)) {
            return Optional.of("its 'of' names '" + of + "', an open entry: a template denotes no type until it is "
                    + "applied (§5.10)");
        }
        return Optional.empty();
    }

    private static boolean reaches(Found constructor, String of) {
        return constructor.name().equals(of) || TypeChain.supertypesOf(constructor.definition()).contains(of);
    }

    /** The entry at the end of {@code name}'s §8.3 chain in {@code entries}, or empty where the walk reaches none. */
    private static Optional<Found> reached(String name, Function<String, Top> entries) {
        String terminal = AliasChain.terminal(name, entries);
        Top definition = entries.apply(terminal);
        return definition == null || definition instanceof Alias ? Optional.empty()
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
