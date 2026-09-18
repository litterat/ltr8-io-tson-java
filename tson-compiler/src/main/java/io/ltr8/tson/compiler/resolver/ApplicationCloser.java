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

    /**
     * The entry a <b>declaration</b> naming a fully-bound application <em>is</em> ({@code SPEC-FEEDBACK.md}
     * #15): the application closed into this declaration's own name, rather than minted under a
     * content-derived one for the declaration to reference.
     *
     * <p>§8.2 gives a declared entry its name as its identity and a minted one its content, "since it has no
     * declared name to be its identity" -- so an application a declaration names needs no derived name, and
     * one no declaration names still gets one. Two declarations of one application are therefore two entries
     * with one structure, exactly as two hand-written records with the same fields are; nothing dedupes them,
     * and a rule that cares (§5.2's pin distinctness over a family's members) catches them downstream on its
     * own terms.
     *
     * <p>Returns {@code null} where this declaration cannot own an entry, and the caller falls back to the
     * reference form: an unresolved or non-template head, an arity mismatch (both the ordinary path's to
     * report), and §5.10's <b>partial application</b>, which mints no entry at all -- the composed
     * application's entry belongs to whoever names it.
     *
     * <p>A {@code default} for {@link #byParameterKind}'s reason: this stays a functional interface, so the
     * meta-kernel bootstrap and the tests over hand-built namespaces keep passing a lambda and keep the
     * reference shape.
     */
    default TypeDefinition closeApplicationInto(String declaredName, TypeRef application) {
        return null;
    }
}
