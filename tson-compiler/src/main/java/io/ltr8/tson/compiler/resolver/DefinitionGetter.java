package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.Top;

/**
 * A single-name entry lookup -- the value a name maps to -- {@code null} if {@code name} isn't resolved (yet or
 * at all), matching {@code Map.get}'s own contract -- {@link DefinitionResolver} holds two of these over the
 * schema's one namespace: {@code namespaceDefinitions}, the whole of it, which {@link
 * DefinitionResolver#resolveComposition}/{@link DefinitionResolver#resolveRefinement}/{@link
 * DefinitionResolver#resolveAtomRefinement} look a supertype/refinement-source/atom-refinement-source up in, and
 * {@code metaDefinitions}, where {@link DefinitionResolver#resolveConstructorTarget} looks a constructor-application
 * target up and an annotation's type is found -- the whole namespace too, but for the kernel's own resolution, which
 * reads every head through its pre-loaded stand-in.
 *
 * <p>A resolution's implementation is its {@link WaitOrder}, which makes a declaration ready the first time it is
 * asked for; elsewhere it is a method reference onto a caller's own {@code Map<String, Top>}, {@code
 * entries::get}/{@code namespace::get}, or a fixed map for one already fully resolved. The map stays entirely
 * caller-owned -- {@link DefinitionResolver} never mutates it, only reads through
 * this interface, so a caller resolving declarations one at a time in a loop still has to {@code put}
 * each result in themselves.
 */
@FunctionalInterface
interface DefinitionGetter {

    Top definition(String name);
}
