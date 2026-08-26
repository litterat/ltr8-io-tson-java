package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TemplateBody;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The one implementation of {@link TemplateBody}: a constructor application in wire form, standing as the
 * body of the template that declares it, unread until materialisation substitutes its parameters away.
 *
 * <p><b>Every held body is an application, so a {@link DataValue} carries all of them.</b> Three
 * normalisations get it there. A bare record body is the {@code !record { fields: [ ... ] }} it denotes
 * (§5.2), rewritten where it is written, so a record template holds an application like any other. A
 * composition is flattened against its supertypes first and the flattened form is held -- the same
 * resolve-then-round-trip-through-{@code TsonObjectWriter} merge atom refinement already performs, and the
 * reason a composition template is normalised in the resolver where a plain record is normalised at desugar.
 * A parameterized atom refinement is not a form at all: §12.1 gives {@code atom-refinement} no parameter
 * list, a refinement of an atom instance having no parameter to take.
 *
 * <p><b>A reference template holds nothing.</b> {@code <B> pair<uuid, B>} keeps the {@code type_ref} with
 * arguments it already resolves to, a parameter in an argument riding the reference channel like any other
 * name -- so {@code TypeDefinition.parameters} being non-empty does not imply a held body, only the reverse.
 *
 * <p><b>Held, so it needs no label for a parameter.</b> A parameter in a value slot is the one thing a typed
 * open vocabulary cannot spell without one -- a bare token there is always a literal, so a
 * {@code param}/{@code value} labelled group is the only way to tell them apart. Nothing here reads the body
 * as constructor vocabulary until materialisation has substituted, so a token is just a token and
 * substitution rewrites the ones that resolve into the entry's {@code parameters} (§8.1's shadowing rule).
 * The cost is the same one shadowing carries everywhere: a literal spelled like a live parameter is
 * unreachable inside that template.
 *
 * <p><b>Named {@code template}, provisionally.</b> Nothing in the kernel declares it -- an open entry never
 * serialises as a {@code type_definition} -- so this only decides what a written body calls itself, and
 * {@code !template { application: !choice { variants: [T error] } }} is a better answer than a lowercased
 * Java class name. Whether it should be the application unwrapped, or a {@code template} the kernel really
 * declares, is {@code SPEC-FEEDBACK.md} #5's to settle.
 *
 * <p><b>A wrapper rather than {@code DataValue} implementing {@link TemplateBody} directly.</b> The AST
 * models surface syntax and the {@code schema.meta} hierarchy models resolved bodies; a value is a body only
 * in this role, and saying so once here keeps the grammar types out of the value model's root hierarchy.
 */
@Typename(name = "template")
public record HeldBody(DataValue application) implements TemplateBody {

    @Override
    public Set<String> names() {
        Set<String> names = new LinkedHashSet<>();
        collect(application.coreValue(), names);
        return names;
    }

    private static void collect(CoreValue value, Set<String> into) {
        switch (value) {
            case TokenValue token when token.form() == TokenForm.UNQUOTED -> into.add(token.text());
            case ArrayValue array -> array.elements()
                    .forEach(element -> collect(element.value().coreValue(), into));
            case RecordValue record -> record.fields()
                    .forEach(field -> collect(field.value().value().coreValue(), into));
            default -> { } // a quoted token is a literal, and nothing else carries a name
        }
    }

    public HeldBody {
        if (application == null) {
            throw new IllegalArgumentException("a template's held body is the application it was written as, "
                    + "and there is always one");
        }
    }
}
