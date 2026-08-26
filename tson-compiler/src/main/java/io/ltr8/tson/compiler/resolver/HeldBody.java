package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.schema.TypeDef;
import io.ltr8.tson.schema.meta.OpenBody;

/**
 * The one implementation of {@link OpenBody}: a declaration's own right-hand side, desugared but not
 * resolved, standing as the body of the open entry it declares.
 *
 * <p><b>A {@link TypeDef} is what is held, because every declaration form can be a template.</b> A record
 * template ({@code <T> { x: T }}), an instance template ({@code <T> !array { element_type: T }} and the sugar
 * that means it), a partial application ({@code <B> pair<uuid, B>}), and a parameterized refinement all reach
 * the resolver as branches of that one hierarchy, so holding it covers them uniformly -- where a head plus a
 * payload would cover only the instance case and force the rest back into a second representation. It also
 * makes an open entry's resolved form fall out: the declaration goes in and the declaration comes back.
 *
 * <p><b>Desugared, and nothing lifted.</b> Sugar expands in place inside a template body -- {@code <T> [T]}
 * is held as {@code !array { element_type: T }}, not verbatim (one template would then have two resolved
 * spellings) and not as a lifted synthetic entry (a form naming a parameter cannot be an entry of its own).
 * Concrete forms lift as usual once materialisation has made them concrete.
 *
 * <p><b>A wrapper rather than {@code TypeDef} implementing {@link OpenBody} directly.</b> The AST models
 * surface syntax and the {@code schema.meta} hierarchy models resolved bodies; a node is a body only in this
 * role, and saying so once here keeps the grammar types out of the value model's root hierarchy.
 */
public record HeldBody(TypeDef declaration) implements OpenBody {

    public HeldBody {
        if (declaration == null) {
            throw new IllegalArgumentException("an open entry's held body is the declaration it was written "
                    + "as, and there is always one");
        }
    }
}
