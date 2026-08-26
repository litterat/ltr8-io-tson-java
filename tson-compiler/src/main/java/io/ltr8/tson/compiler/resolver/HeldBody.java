package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The one implementation of {@link TemplateBody}: a constructor application in wire form, standing as the
 * body of the template that declares it, unread until materialisation substitutes its parameters away.
 *
 * <p><b>Every held body is an application, so a {@link DataValue} carries all of them.</b> A sugar form
 * already is one, by the desugar table. A bare record body becomes one: it is the
 * {@code !record { fields: [ ... ] }} §5.2 says it denotes, and {@code SchemaDesugarer} rewrites it there,
 * where the body is written, so a record template holds an application like every other open form and one
 * process closes them all.
 *
 * <p><b>The rewrite belongs in the desugar phase and nowhere else.</b> The alternative -- resolve the body,
 * then write the resolved form back out -- puts a second producer in front of a wire form that two later
 * phases read, and they do not agree: {@code TsonObjectWriter} states a no-argument {@code type_ref} in the
 * explicit record form where the desugar table states it positionally, which leaves a {@code type_argument}
 * indistinguishable from a {@code type_ref} application to a walk that reads neither against a vocabulary.
 * The entry name is derived from what is written, so a second spelling is also a second entry for one type.
 *
 * <p><b>A composition or refinement template is held too, from one phase later.</b> Both absorb fields from a
 * source (§5.8's supertypes, §5.7's refinement source), so the form to hold is the <em>flattened</em> one --
 * a §5.7 tightening entry states a modifier and no type-ref, and is not a {@code record_field} at all until
 * the inherited field supplies one. So {@code DefinitionResolver} resolves the body against the namespace and
 * then writes it back through {@code SchemaDesugarer.heldRecord}, which is the same spelling by construction.
 * A parameterized <b>atom refinement</b> is not a form at all: §12.1 gives {@code atom-refinement} no
 * parameter list, a refinement of an atom instance having no parameter to take.
 *
 * <p><b>Which is why the kernel declares one {@code value} slot and no labelled group.</b> Every open body is
 * held, so a routed parameter rides {@code value} like any other token and {@code record_field.value_param}
 * had nothing left to disambiguate; it is gone, along with {@code instance_template} and
 * {@code template_argument}, the vocabulary that quoted an open body slot by slot.
 *
 * <p><b>An alias holds one too.</b> {@code <B> pair<uuid, B>} is the {@code !reference { target: pair<uuid,
 * B> }} §8.1 says it denotes, which is spellable because {@code reference.target} is a {@code type_ref}. So
 * {@code TypeDefinition.parameters} being non-empty and the body being held now imply each other, with no
 * exception left -- which is what lets materialisation dispatch on the constructor head rather than on what
 * shape the body happened to arrive in.
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

    @Override
    public List<TypeRef> applications() {
        List<TypeRef> applications = new ArrayList<>();
        collectApplications(application.coreValue(), applications);
        return applications;
    }

    /**
     * Every {@code type_ref} record form the held tree holds. It does <b>not</b> descend into one it finds:
     * an application's own arguments come back inside the {@link TypeRef} it yields, and the caller that
     * cares about nesting walks those -- descending here as well would report each nested application twice.
     */
    private static void collectApplications(CoreValue value, List<TypeRef> into) {
        switch (value) {
            case RecordValue record when TemplateMaterialiser.isApplication(record) ->
                    into.add(TemplateMaterialiser.typeRefOf(record));
            case RecordValue record -> record.fields()
                    .forEach(field -> collectApplications(field.value().value().coreValue(), into));
            case ArrayValue array -> array.elements()
                    .forEach(element -> collectApplications(element.value().coreValue(), into));
            default -> { } // a token names a type but applies nothing
        }
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
