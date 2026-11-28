package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.VoidType;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Questions asked of a chain of alias entries ([TSON-SCHEMA] §8.3) over a namespace a resolver or linker holds as a map
 * or a lookup. The walk itself is {@link Alias#terminal}'s, stated once for every module.
 *
 * <p><b>The walk stops at three things</b>: an entry whose body is not an {@link Alias}, the type at the end; a name
 * the namespace does not declare; and a cycle. {@link #terminal} answers the last two with the name it stopped at,
 * since an undeclared name is a type parameter to its caller and a cycle's answer depends on where the walk began --
 * so no false equality follows. {@link #terminalDefinition} answers both with an empty {@code Optional}, having been
 * asked for an entry and having none to give.
 *
 * <p><b>Not every walk over references is this one.</b> {@code ParameterTypes} follows a chain to reach a
 * slot's declared body and keeps its own loop, being after the constructor's own vocabulary, where the parametric
 * record is the answer.
 */
public final class AliasChain {

    private AliasChain() {
    }

    /**
     * The name at the end of {@code name}'s alias chain -- {@code name} itself when it starts one, and
     * the name the walk stopped at when it cannot reach a type (see this class's own note).
     */
    public static String terminal(String name, Map<String, ? extends Top> entries) {
        return terminal(name, entries::get);
    }

    /**
     * Whether {@code annotations} holds one whose name reaches {@code target} through its alias hops: an alias of
     * the kernel's {@code meta.disjoint} names it as {@code @meta.disjoint} does, {@code meta.disjoint} itself being an
     * alias of {@code void}, so the walk asks whether {@code target} is on the chain, not at its end.
     */
    public static boolean annotates(io.ltr8.annotation.Annotations annotations, String target,
                                    Map<String, ? extends Top> entries) {
        return annotations.values().stream().anyMatch(a -> passesThrough(a.name(), target, entries));
    }

    /** Whether {@code name}'s alias chain, {@code name} itself included, holds {@code target}. */
    public static boolean passesThrough(String name, String target, Map<String, ? extends Top> entries) {
        Set<String> walked = new LinkedHashSet<>();
        String current = name;
        while (walked.add(current)) {
            if (current.equals(target)) {
                return true;
            }
            if (entries.get(current) instanceof Alias alias) {
                current = alias.target().name();
                continue;
            }
            return false;
        }
        return false;
    }

    /**
     * The same walk over a namespace still being built, which has a lookup rather than a finished map --
     * {@code ParametricMaterialiser} normalises an application's arguments through this while resolution runs.
     */
    public static String terminal(String name, Function<String, ? extends Top> entries) {
        return Alias.terminal(name, entries);
    }

    /** The entry at the end of {@code name}'s chain, or empty where the walk reaches no type. */
    public static Optional<Top> terminalDefinition(String name, Map<String, ? extends Top> entries) {
        return terminalDefinition(name, entries::get);
    }

    /** The same over a lookup -- a schema's {@code Namespace} of entries, or a namespace still being built. */
    public static Optional<Top> terminalDefinition(String name, Function<String, ? extends Top> entries) {
        Top definition = entries.apply(Alias.terminal(name, entries));
        return definition instanceof Alias ? Optional.empty() : Optional.ofNullable(definition);
    }

    /**
     * Whether {@code name}'s chain ends at a {@code void} -- the kernel's, core's sibling, or any other {@code
     * !meta.void_type} -- told by the body's constructor rather than by the name the chain ends at.
     */
    public static boolean resolvesToVoid(String name, Map<String, ? extends Top> entries) {
        return terminalDefinition(name, entries).map(d -> d instanceof VoidType).orElse(false);
    }
}
