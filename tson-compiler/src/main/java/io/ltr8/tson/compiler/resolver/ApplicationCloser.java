package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

/**
 * {@code TemplateMaterialiser}'s on-demand half, seen by {@link DefinitionResolver} as a constructor-fixed
 * dependency the way its two namespace getters are: what a declaration needs of a §5.10 application while it
 * resolves, before the rest of materialisation runs as a whole-schema pass afterwards.
 */
@FunctionalInterface
interface ApplicationCloser {

    /**
     * The name a fully-bound application at a refinement source leaves, where its head, past its alias hops, is no
     * open record: a construction there is refused, as at any slot that holds a name, and so is an application of a
     * head that takes no parameters; one whose head names nothing is left for the linker. An application of an open
     * record at a refinement source is absorbed by value and never reaches here.
     */
    String closeApplication(TypeRef application);

    /**
     * §5.10's call-site check, for an application absorbed <b>by value</b> rather than closed: each argument must
     * name a type that IS-A its parameter's bound. A composition operand or refinement source is absorbed and
     * never closes, so it never reaches the materialiser's own check, and would otherwise go unchecked.
     *
     * <p>A {@code default} rather than a second abstract method, so this stays a functional interface: the
     * resolvers built without a materialiser -- the kernel bootstrap, and unit tests over hand-built
     * namespaces -- pass a lambda, and an operand there is not checked.
     */
    default void checkArguments(String head, Top template, List<TypeRef> arguments) {
    }

    /**
     * The entry a <b>declaration</b> naming a fully-bound application <em>is</em> (§8.2): the open record's
     * application closed into this declaration's own name, its key recording the application.
     *
     * <p>§8.2 gives a declared entry its name as its identity, so two declarations of one application are two
     * entries with one structure, exactly as two hand-written records with the same fields are, and a rule that
     * cares (§5.2's pin distinctness over a family's members) catches them on its own terms.
     *
     * <p>Returns {@code null} where this declaration cannot own an entry, and the caller falls back to the
     * reference form: an unresolved or non-template head, or an arity mismatch, both the ordinary path's to report.
     *
     * <p>A {@code default} for {@link #checkArguments}'s reason: this stays a functional interface, so the
     * kernel bootstrap and the tests over hand-built namespaces keep passing a lambda and keep the
     * reference shape.
     */
    default Top closeApplicationInto(String declaredName, TypeRef application) {
        return null;
    }
}
