package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.util.List;

/**
 * The kernel's {@code meta.completion}, the value the resolver states on the key of an entry declared as a completion
 * of an incomplete entry ([TSON-SCHEMA] §8.1): {@code of} is the incomplete entries it closed, nearest first, each by
 * its own name past alias hops. The names are text: those entries are in no resolved form, so nothing judges them.
 */
@Typename(name = "meta.completion")
public record Completion(List<String> of) {

    public Completion {
        of = List.copyOf(of);
        if (of.isEmpty()) {
            throw new IllegalArgumentException("a completion closes at least one incomplete entry");
        }
    }
}
