package io.ltr8.tson.schema;

import java.util.Optional;

/**
 * Resolves a {@code !!import} target to an already-registered {@link TsonLinkedSchema}, keyed by
 * canonical identity ({@code [TSON-DATA] §2.2.1}) rather than the raw URI a document wrote -- the
 * same identity {@link TsonSchemaRegistry#register} keys its own entries under, so a loader and the
 * registry it's attached to always agree on what "the same schema" means. Returns a {@link
 * TsonLinkedSchema}, not a plain {@link TsonSchema}, because {@link TsonSchemaRegistry} only ever
 * stores linked schemas -- there's no other kind of thing a lookup by identity could find.
 *
 * <p>{@link TsonSchemaRegistry}'s own no-arg constructor uses a default implementation that only ever
 * finds an *already-registered* schema (nothing is fetched from anywhere) -- matching Part 2
 * §10.1's own precedence order, where "fetched" population is opt-in and disabled by default. A
 * caller wanting to resolve an import that isn't registered yet (e.g. fetching schema content over
 * the network, or reading it from a local file) supplies their own {@code TsonSchemaLoader} instead;
 * {@code TsonSchemaRegistry} doesn't merge a loaded import's entries into an importer's namespace yet
 * (see {@code TsonSchemaRegistry}'s own Javadoc for the current boundary) -- this interface exists so
 * that later work doesn't need an API change once it does.
 */
@FunctionalInterface
public interface TsonSchemaLoader {

    Optional<TsonLinkedSchema> load(String canonicalIdentity);
}
