package io.ltr8.tson.compiler.resolver;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.RecordTemplateBody;
import io.ltr8.tson.schema.meta.RecordTemplateField;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.SiblingRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A record template's parameters substituted on the model ([TSON-SCHEMA] §5.10): the template's fields and
 * parents with each parameter replaced by the argument bound to it. A {@code type_param} takes its argument as the
 * field's {@code type}, and a parameter standing as an argument of an application in a {@code type} or a parent is
 * replaced where it stands; no other fact of a field changes.
 *
 * <p><b>Two callers, one rule.</b> {@code TemplateMaterialiser} closes an application, where every argument is
 * closed and the result is a member's fields ({@link #closed}); so does {@code DefinitionResolver} where a closed
 * declaration absorbs an applied operand. Where the absorbing declaration is itself open and the operand is applied
 * to its parameters ({@code <T> box<T> & { … }}), {@code outer} names those parameters and an argument that is one
 * of them lands as a label again: the result is the absorbing template's fields ({@link #fields}).
 *
 * <p>Every argument is a reference (§5.10), so every binding is one a type slot takes; whether it names a type that
 * IS-A the parameter's bound is {@code TemplateMaterialiser.checkArguments}'.
 */
final class RecordTemplateSubstitution {

    private RecordTemplateSubstitution() {
    }

    /**
     * {@code template}'s fields with {@code bindings} substituted, in order and with their key annotations, an
     * argument that names one of {@code outer} landing as a label. {@code head} names the application for a refusal.
     */
    static AnnotatedMap<String, RecordTemplateField> fields(String head, RecordTemplateBody template,
            Map<String, TypeRef> bindings, List<String> outer) {
        AnnotatedMap<String, RecordTemplateField> substituted = new AnnotatedMap<>();
        template.fields().forEach((name, field) -> substituted.put(name,
                field(field, bindings, outer), template.fields().getAnnotations(name)));
        return AnnotatedMap.copyOf(substituted);
    }

    /**
     * {@code template}'s fields with every parameter substituted by its closed argument, each the {@link RecordField}
     * a member holds, in order and with their key annotations.
     */
    static AnnotatedMap<String, RecordField> closed(String head, RecordTemplateBody template,
            Map<String, TypeRef> bindings) {
        AnnotatedMap<String, RecordField> closed = new AnnotatedMap<>();
        template.fields().forEach((name, field) -> closed.put(name,
                field(field, bindings, List.of()).closed(), template.fields().getAnnotations(name)));
        return AnnotatedMap.copyOf(closed);
    }

    /** {@code template}'s parents as written, each argument substituted -- closing completes the chain from them. */
    static List<TypeRef> parents(RecordTemplateBody template, Map<String, TypeRef> bindings) {
        return template.parents().stream().map(parent -> reference(parent, bindings)).toList();
    }

    /** {@code ref} with every argument naming a parameter replaced by its binding, at any depth. */
    static TypeRef reference(TypeRef ref, Map<String, TypeRef> bindings) {
        if (ref.arguments().isEmpty()) {
            return ref;
        }
        List<TypeRef> arguments = ref.arguments().stream().map(argument ->
                argument.arguments().isEmpty() && bindings.containsKey(argument.name())
                        ? bindings.get(argument.name()) : reference(argument, bindings)).toList();
        return TypeRef.of(ref.name(), arguments, ref.annotations(), ref.closure());
    }

    private static RecordTemplateField field(RecordTemplateField field, Map<String, TypeRef> bindings,
                                             List<String> outer) {
        // A sibling names a field of the record, never a parameter, so substitution leaves it as written.
        Optional<Reference> type = field.type().map(written -> switch (written) {
            case TypeRef reference -> reference(reference, bindings);
            case SiblingRef sibling -> sibling;
        });
        Optional<String> typeParam = Optional.empty();
        if (field.typeParam().isPresent()) {
            TypeRef argument = bound(field.typeParam().get(), bindings);
            if (argument.arguments().isEmpty() && outer.contains(argument.name())) {
                typeParam = Optional.of(argument.name());
            } else {
                type = Optional.of(argument);
            }
        }
        return new RecordTemplateField(type, typeParam, field.optional(), field.voidable(), field.role(),
                field.value(), field.annotations(), field.position());
    }

    private static TypeRef bound(String parameter, Map<String, TypeRef> bindings) {
        TypeRef argument = bindings.get(parameter);
        if (argument == null) {
            throw new IllegalStateException("parameter '" + parameter + "' has no argument -- arity is checked "
                    + "before a template is substituted");
        }
        return argument;
    }
}
