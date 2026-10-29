package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.Top;

/**
 * A single-name entry lookup -- the value a name maps to -- {@code null} if {@code name} isn't resolved (yet or
 * at all), matching {@code Map.get}'s own contract -- {@link DefinitionResolver} holds two of these over the
 * schema's one namespace: {@code namespaceDefinitions}, the whole of it, which {@link
 * DefinitionResolver#resolveComposition}/{@link DefinitionResolver#resolveRefinement}/{@link
 * DefinitionResolver#resolveAtomRefinement} look a supertype/refinement-source/atom-refinement-source up in, and
 * {@code metaDefinitions}, its imported part, which {@link DefinitionResolver#resolveConstructorTarget} looks a
 * constructor-application target up in, since only an imported entry has a compiled reader yet.
 *
 * <p>Every real implementation is a method reference straight onto some caller's own {@code
 * Map<String, Top>} -- {@code entries::get}/{@code namespace::get} for a namespace that's
 * still growing one declaration at a time, or a fixed map for one that's already fully resolved (true of the
 * imported part always, since every import resolves before the importer does). The
 * map stays entirely caller-owned -- {@link DefinitionResolver} never mutates it, only reads through
 * this interface, so a caller resolving declarations one at a time in a loop still has to {@code put}
 * each result in themselves.
 */
@FunctionalInterface
interface DefinitionGetter {

    Top definition(String name);
}
