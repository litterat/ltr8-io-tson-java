package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * The kernel's {@code alias} constructor's own vocabulary, resolved (Part 2 §4.1, §8.1): an alias entry's
 * body, {@code !meta.alias { target: E }} -- the kernel's aliasing shape, used by {@code meta.type_name}/{@code
 * meta.param_name} (aliasing {@code meta.identifier}), the annotation markers {@code annotation}/{@code doc}. It
 * composes {@code top} directly and so carries no IS-A chain: a hop is not a type of its own.
 *
 * <p><b>{@code target} is a reference, typed {@code meta.type_ref}, and only a name stands there</b> ([TSON-SCHEMA]
 * §4.2). A declaration that names an application, {@code text_box => box<text>}, is no alias: it is the record the
 * application closes to, under the declared name. An application written at a target closes nowhere and is refused.
 * No alias has parameters: a parametric record's body is a record ([TSON-SCHEMA] §5.10).
 */
@Typename(name = "meta.alias")
public record Alias(TypeRef target) implements Top {

    /**
     * The name at the end of {@code name}'s alias chain in {@code entries} ([TSON-SCHEMA] §8.3): {@code name} itself
     * where it starts none, and the name the walk stopped at where it reaches nothing or closes on itself, so no false
     * equality follows from a chain with no terminal. A lookup rather than a map, so a namespace still being built is
     * walked as a finished one is.
     */
    public static String terminal(String name, Function<String, ? extends Top> entries) {
        Set<String> walked = new HashSet<>();
        String current = name;
        while (walked.add(current) && entries.apply(current) instanceof Alias alias) {
            current = alias.target().name();
        }
        return current;
    }
}
