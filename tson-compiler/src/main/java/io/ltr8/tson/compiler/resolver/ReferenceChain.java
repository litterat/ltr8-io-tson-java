package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Following a chain of {@code REFERENCE} entries to the type at the end of it -- [TSON-SCHEMA] §8.3's walk,
 * stated once.
 *
 * <p><b>Why this is a shared class rather than a loop each caller writes.</b> Resolved output states the
 * chain as the author wrote it: a use site naming an alias names the alias, and a processor collapses the
 * chain when it needs the type. That makes the walk the only mechanism, and four passes were doing it --
 * the linker's choice-variant distinctness and its §5.2 field-value check, subtype naming, and
 * discrimination-class classification. Written four times, the one decision inside it (below) was four
 * decisions that could drift apart silently.
 *
 * <p><b>The walk stops at three things</b>, and the second is the one worth knowing:
 * <ul>
 *   <li>an entry whose body is not a {@link Reference} -- the type at the end;
 *   <li>an <b>argument-bearing</b> target, which is an application rather than a hop to another entry:
 *       there is no entry at the end of one until materialisation mints it;
 *   <li>a name the namespace does not declare, and a cycle. {@link #terminal} answers both with the name it
 *       stopped at, since an undeclared name is a type parameter to its caller and a cycle's answer depends
 *       on where the walk began -- so no false equality follows. {@link #terminalDefinition} answers both
 *       with an empty {@code Optional}, having been asked for an entry and having none to give.
 * </ul>
 *
 * <p><b>Not every walk over references is this one.</b> {@code ParameterKinds} follows a chain to reach a
 * slot's declared body and deliberately does <em>not</em> stop at an argument-bearing target -- it is after
 * the constructor's own vocabulary, where the template is the answer. It keeps its own loop, and the
 * difference is the reason to say so here rather than let a future reader assume the four were five.
 */
public final class ReferenceChain {

    private ReferenceChain() {
    }

    /**
     * The name at the end of {@code name}'s reference chain -- {@code name} itself when it starts one, and
     * the name the walk stopped at when it cannot reach a type (see this class's own note).
     */
    public static String terminal(String name, Map<String, TypeDefinition> entries) {
        return terminal(name, entries::get);
    }

    /**
     * The same walk over a namespace still being built, which has a lookup rather than a finished map --
     * {@code TemplateMaterialiser} normalises an application's arguments through this while resolution runs.
     */
    public static String terminal(String name, Function<String, TypeDefinition> entries) {
        return walk(name, entries).name();
    }

    /** The entry at the end of {@code name}'s chain, or empty where the walk reaches no type. */
    public static Optional<TypeDefinition> terminalDefinition(String name, Map<String, TypeDefinition> entries) {
        Stop stop = walk(name, entries::get);
        return stop.reached() ? Optional.ofNullable(entries.get(stop.name())) : Optional.empty();
    }

    /** Where the walk stopped, and whether it stopped on a type rather than on nothing or on itself. */
    private record Stop(String name, boolean reached) {
    }

    private static Stop walk(String name, Function<String, TypeDefinition> entries) {
        Set<String> walked = new LinkedHashSet<>();
        String current = name;
        while (walked.add(current)) {
            TypeDefinition definition = entries.apply(current);
            if (definition == null) {
                return new Stop(current, false);
            }
            if (definition.body() instanceof Reference reference && reference.target().arguments().isEmpty()) {
                current = reference.target().name();
                continue;
            }
            return new Stop(current, true);
        }
        return new Stop(current, false); // a cycle has no terminal
    }
}
