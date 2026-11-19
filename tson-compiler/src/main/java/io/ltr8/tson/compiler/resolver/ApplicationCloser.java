package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.ApplyBody;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

/**
 * {@code TemplateMaterialiser}'s on-demand half, seen by {@link DefinitionResolver} as a constructor-fixed
 * dependency the way its two namespace getters are: what a declaration needs of a §5.10 application while it
 * resolves, before the rest of materialisation runs as a whole-schema pass afterwards.
 */
interface ApplicationCloser {

    /**
     * §5.10's call-site check, for an application absorbed <b>by value</b> rather than closed: each argument must
     * name a type that IS-A its parameter's bound. A composition operand is absorbed and never closes, so it never
     * reaches the materialiser's own check, and would otherwise go unchecked.
     */
    void checkArguments(String head, Top template, List<TypeRef> arguments);

    /**
     * The entry a <b>declaration</b> naming a fully-bound application <em>is</em> (§8.2): the parametric record's
     * application closed into this declaration's own name, its key recording the application.
     *
     * <p>§8.2 gives a declared entry its name as its identity, so two declarations of one application are two
     * entries with one structure, exactly as two hand-written records with the same fields are, and a rule that
     * cares (§5.2's pin distinctness over a family's members) catches them on its own terms.
     *
     * <p>Returns {@code null} where this declaration cannot own an entry, and the caller falls back to the
     * reference form: an unresolved head or one that is no parametric record, or an arity mismatch, both the ordinary
     * path's to report.
     */
    Top closeApplicationInto(String declaredName, ApplyBody application);
}
