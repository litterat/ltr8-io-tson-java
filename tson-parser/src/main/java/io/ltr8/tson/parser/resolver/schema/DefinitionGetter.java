package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.schema.meta.TypeDefinition;

/**
 * The type-name namespace lookup {@link DefinitionResolver#resolveComposition}/{@link
 * DefinitionResolver#resolveRefinement}/{@link DefinitionResolver#resolveAtomRefinement} need to
 * find an already-resolved supertype/refinement-source/atom-refinement-source by name -- {@code
 * null} if {@code name} isn't resolved (yet or at all), matching {@code Map.get}'s own contract,
 * since every real implementation of this interface today is a method reference straight onto a
 * caller's own {@code Map<String, TypeDefinition>} (typically {@code entries::get}, where {@code
 * entries} is the very map that same caller is incrementally filling in as it resolves one
 * declaration at a time).
 *
 * <p>Replaces a bare {@code Map<String, TypeDefinition> resolved} parameter threaded through every
 * declaration-level method (a required constructor parameter of {@link DefinitionResolver} instead,
 * 2026-07-27, on the user's own explicit direction, mirroring {@link DefinitionMetaReader}'s own
 * "required constructor parameter" precedent) -- a caller who already owns a growing namespace map
 * (a hand-built {@code resolved} map in a test, {@link MetaKernelBootstrapResolver}'s own two-pass
 * {@code entries}, {@link TsonSchemaResolver#resolveSchema}'s own {@code namespace}) constructs the
 * resolver with a lookup closing over that exact map, typically a one-line method reference. The map
 * itself stays entirely caller-owned -- {@link DefinitionResolver} never mutates it, only reads
 * through this interface, so a caller resolving declarations one at a time in a loop still has to
 * {@code put} each result in themselves, same as before this interface existed.
 */
@FunctionalInterface
interface DefinitionGetter {

    TypeDefinition getTypeDefinition(String name);
}
