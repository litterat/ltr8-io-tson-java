package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.schema.TypeDef;
import io.ltr8.tson.schema.meta.TemplateBody;

/**
 * The one implementation of {@link TemplateBody}: a declaration's own right-hand side, desugared but not
 * resolved, standing as the body of the open entry it declares.
 *
 * <p><b>A {@link TypeDef} is what is held, because it is the door every declaration form comes back through.</b>
 * {@code DefinitionResolver.resolveTypeDef} takes one and routes all five branches, so materialisation
 * re-enters resolution exactly where the declaration entered it the first time, with no routing of its own.
 * A {@code DataValue} would not do: it is data grammar, and only an instance template
 * ({@code <T> !array { element_type: T }} and the sugar meaning it) is spelled in data. A record template's
 * field modifiers ({@code ?}, {@code ~}, {@code =}), a partial application's argument list
 * ({@code <B> pair<uuid, B>}), and a refinement's {@code ^} have no data spelling at all, so holding a
 * {@code DataValue} would hold the instance case and leave the other three in a second representation --
 * the split this whole design exists to remove. It also makes a template's resolved form fall out: the
 * declaration goes in and the declaration comes back.
 *
 * <p><b>Desugared, and nothing lifted.</b> Sugar expands in place inside a template body -- {@code <T> [T]}
 * is held as {@code !array { element_type: T }}, not verbatim (one template would then have two resolved
 * spellings) and not as a lifted synthetic entry (a form naming a parameter cannot be an entry of its own).
 * Concrete forms lift as usual once materialisation has made them concrete.
 *
 * <p><b>A wrapper rather than {@code TypeDef} implementing {@link TemplateBody} directly.</b> The AST models
 * surface syntax and the {@code schema.meta} hierarchy models resolved bodies; a node is a body only in this
 * role, and saying so once here keeps the grammar types out of the value model's root hierarchy.
 */
public record HeldBody(TypeDef declaration) implements TemplateBody {

    public HeldBody {
        if (declaration == null) {
            throw new IllegalArgumentException("an open entry's held body is the declaration it was written "
                    + "as, and there is always one");
        }
    }
}
