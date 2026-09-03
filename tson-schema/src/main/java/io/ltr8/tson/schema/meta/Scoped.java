package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * meta.tn's {@code scoped} constructor: the open sum, in which the value names its own type and the
 * instance names the namespaces that name may be resolved in.
 *
 * <p>The distinction against {@link ChoiceBody} is closed against open. A choice enumerates its variants;
 * a scoped instance names where variants are drawn from, which is why {@code disjoint} is absent here as
 * on every non-choice sum ([TSON-SCHEMA] §8.1) and why {@code DiscriminationClass} treats one as classless.
 *
 * <p>{@link #scope} says which namespaces are admitted. {@link #schemas} narrows the foreign ones: absent is
 * any foreign schema, a keyed map is those schemas, and a key whose value is an empty list is every type that
 * schema declares where a non-empty list is those types. The empty list carries "absent" for the inner value
 * unambiguously, the schema typing it {@code [type_name; 1..]?} -- a list that is present is never empty. The
 * outer {@link java.util.Optional} cannot be collapsed the same way, the map itself carrying {@code
 * min_items: 1}.
 *
 * <p>Keys are compared by canonical identity ([TSON-DATA] §2.2.1), so a pinned key and an unpinned {@code
 * !!schema} in the data match; each pin is verified by the loader on its own, as any other reference's is.
 *
 * <p>Core's three instances are the three subsets that have a name -- {@code declared} ({@code LOCAL}),
 * {@code extern} ({@code EXTERN}) and {@code dynamic} (both) -- and its two templates, {@code extern_of}
 * and {@code extern_type}, are the per-use narrowings, so naming one schema or one type in it is an
 * application rather than a declaration.
 *
 * <p>Pure constraint values, no reading behaviour: the reader a scoped position needs is one piece of
 * machinery over {@code TsonDataStream}'s {@code SchemaRef} event, shared by every instance, and it is not
 * built -- {@code ValueReaderFactoryRegistry} maps this constructor to an {@code ErrorReader}, so a schema
 * declaring one compiles and the first read of a value against it reports {@code NOT_IMPLEMENTED}.
 */
@Typename(name = "scoped")
public record Scoped(List<ScopeKind> scope, Optional<Map<URI, List<String>>> schemas) implements Sum {

    public Scoped {
        scope = List.copyOf(scope);
        schemas = schemas.map(entries -> Collections.unmodifiableMap(new LinkedHashMap<>(entries)));
    }

    /** Whether this position admits a value whose type comes from {@code cell}'s namespace. */
    public boolean admits(ScopeKind cell) {
        return scope.contains(cell);
    }

    /**
     * {@inheritDoc}
     *
     * <p>One coherence rule, of the family a resolver already runs over {@code min_items}/{@code
     * max_items}: naming the foreign schemas that are welcome says nothing unless foreign schemas are
     * admitted at all. The state admitting nothing needs no rule -- {@code min_items: 1} on both
     * collections makes it unspellable rather than refused.
     */
    @Override
    public List<String> coherenceCheck() {
        if (schemas.isPresent() && !admits(ScopeKind.EXTERN)) {
            return List.of("schemas narrows the foreign schemas admitted here, but scope does not hold EXTERN");
        }
        return List.of();
    }
}
