package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

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

    /**
     * §5.10's argument kinds, for an application absorbed <b>by value</b> rather than closed.
     *
     * <p>A composition operand mints no entry, so it never reaches the materialiser -- and it needs the one
     * thing closing did for it besides making an entry: §12.1 decides an argument's channel by the shape of
     * the token that spells it, so an unquoted {@code red} arrives as a reference, and only the kind of the
     * parameter it binds says it is an enum member instead. Without this, {@code t<red>} against
     * {@code t => <M> { a: st ~ M }} writes a type reference into a value slot.
     *
     * <p>A {@code default} rather than a second abstract method, so this stays a functional interface: the
     * resolvers built without a materialiser -- the meta-kernel bootstrap, and unit tests over hand-built
     * namespaces -- pass a lambda, and an operand there classifies as §12.1 spelled it.
     */
    default List<TypeArgument> byParameterKind(String head, TypeDefinition template,
            List<String> parameters, List<TypeArgument> arguments) {
        return arguments;
    }
}
