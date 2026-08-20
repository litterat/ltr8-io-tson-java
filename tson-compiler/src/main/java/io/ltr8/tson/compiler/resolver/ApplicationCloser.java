package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.TypeRef;

/**
 * Closes a fully-bound §5.10 template application into the entry it denotes, returning that entry's name --
 * {@code TemplateMaterialiser}'s on-demand half, seen by {@link DefinitionResolver} as a
 * constructor-fixed dependency the way its two namespace getters are.
 *
 * <p>It exists because two positions absorb a supertype's <em>fields</em> rather than merely naming a type:
 * a composition supertype (§5.8) and a refinement source (§5.7). Both are resolved per declaration, while
 * the rest of materialisation is a whole-schema pass that runs afterwards -- so without this the entry an
 * application denotes does not exist yet at the moment its fields are needed.
 */
@FunctionalInterface
interface ApplicationCloser {

    String closeApplication(TypeRef application);
}
