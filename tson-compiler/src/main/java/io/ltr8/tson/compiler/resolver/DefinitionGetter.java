package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.TypeDefinition;

/**
 * A single-name {@code TypeDefinition} lookup, {@code null} if {@code name} isn't resolved (yet or
 * at all), matching {@code Map.get}'s own contract -- {@link DefinitionResolver} holds two of these,
 * one per namespace §3.3.1 distinguishes: {@code namespaceDefinitions} (the type-name namespace
 * {@link DefinitionResolver#resolveComposition}/{@link DefinitionResolver#resolveRefinement}/{@link
 * DefinitionResolver#resolveAtomRefinement} look a supertype/refinement-source/atom-refinement-source
 * up in) and {@code metaDefinitions} (the structure namespace {@link
 * DefinitionResolver#resolveConstructorTarget} looks a constructor-application target up in) --
 * genuinely different namespaces that happen to share this same lookup shape, not two names for the
 * same thing.
 *
 * <p>Every real implementation is a method reference straight onto some caller's own {@code
 * Map<String, TypeDefinition>} -- {@code entries::get}/{@code namespace::get} for a namespace that's
 * still growing one declaration at a time, or {@code someCompiledSchema.schema().entries()::get} for
 * one that's already fully resolved and fixed (true of the structure namespace always, since a
 * governing meta-schema is compiled before anything ever asks it a constructor-target question). The
 * map stays entirely caller-owned -- {@link DefinitionResolver} never mutates it, only reads through
 * this interface, so a caller resolving declarations one at a time in a loop still has to {@code put}
 * each result in themselves.
 */
@FunctionalInterface
interface DefinitionGetter {

    TypeDefinition getTypeDefinition(String name);
}
