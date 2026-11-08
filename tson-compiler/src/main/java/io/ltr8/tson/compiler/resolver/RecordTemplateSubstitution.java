package io.ltr8.tson.compiler.resolver;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.tson.atom.IdentifierGrammar;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.RecordTemplateBody;
import io.ltr8.tson.schema.meta.RecordTemplateField;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.SiblingRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A record template's parameters substituted on the model ([TSON-SCHEMA] §5.10): the template's fields and
 * parents with each parameter replaced by the argument bound to it. A {@code type_param} takes a type argument
 * as the field's {@code type}, a {@code value_param} a value argument as its {@code value}, and a parameter
 * standing as an argument of an application in a {@code type} or a parent is replaced where it stands; no other
 * fact of a field changes.
 *
 * <p><b>Two callers, one rule.</b> {@code TemplateMaterialiser} closes an application, where every argument is
 * closed and the result is a member's fields ({@link #closed}); so does {@code DefinitionResolver} where a closed
 * declaration absorbs an applied operand. Where the absorbing declaration is itself open and the operand is applied
 * to its parameters ({@code <T> box<T> & { … }}), {@code outer} names those parameters and an argument that is one
 * of them lands as a label again: the result is the absorbing template's fields ({@link #fields}).
 *
 * <p><b>The argument's kind is checked here</b>, because a label says which kind its slot takes: a value where a
 * type parameter stands ({@code box<3>}) and an application where a value parameter stands are refused at the
 * application that wrote them. Whether a value argument is a value of the field's type is the parameter's
 * recorded type's question ({@code TemplateMaterialiser.checkArguments}) and the closed member's
 * ({@code TsonSchemaLinker.checkFieldValue}).
 */
final class RecordTemplateSubstitution {

    private RecordTemplateSubstitution() {
    }

    /**
     * {@code template}'s fields with {@code bindings} substituted, in order and with their key annotations, an
     * argument that names one of {@code outer} landing as a label. {@code head} names the application for a refusal.
     */
    static AnnotatedMap<String, RecordTemplateField> fields(String head, RecordTemplateBody template,
            Map<String, TypeArgument> bindings, List<String> outer) {
        AnnotatedMap<String, RecordTemplateField> substituted = new AnnotatedMap<>();
        template.fields().forEach((name, field) -> substituted.put(name,
                field(head, field, bindings, outer), template.fields().getAnnotations(name)));
        return AnnotatedMap.copyOf(substituted);
    }

    /**
     * {@code template}'s fields with every parameter substituted by its closed argument, each the {@link RecordField}
     * a member holds, in order and with their key annotations.
     */
    static AnnotatedMap<String, RecordField> closed(String head, RecordTemplateBody template,
            Map<String, TypeArgument> bindings) {
        AnnotatedMap<String, RecordField> closed = new AnnotatedMap<>();
        template.fields().forEach((name, field) -> closed.put(name,
                field(head, field, bindings, List.of()).closed(), template.fields().getAnnotations(name)));
        return AnnotatedMap.copyOf(closed);
    }

    /** {@code template}'s parents as written, each argument substituted -- closing completes the chain from them. */
    static List<TypeRef> parents(RecordTemplateBody template, Map<String, TypeArgument> bindings) {
        return template.parents().stream().map(parent -> reference(parent, bindings)).toList();
    }

    /** {@code ref} with every argument naming a parameter replaced by its binding, at any depth. */
    static TypeRef reference(TypeRef ref, Map<String, TypeArgument> bindings) {
        if (ref.arguments().isEmpty()) {
            return ref;
        }
        List<TypeArgument> arguments = ref.arguments().stream().map(argument -> switch (argument) {
            case TypeArgument.Ref nested when nested.ref().arguments().isEmpty()
                    && bindings.containsKey(nested.ref().name()) -> bindings.get(nested.ref().name());
            case TypeArgument.Ref nested -> (TypeArgument) new TypeArgument.Ref(reference(nested.ref(), bindings));
            case TypeArgument.Value value when value.value().form() == Token.Form.UNQUOTED
                    && bindings.containsKey(value.value().text()) -> bindings.get(value.value().text());
            case TypeArgument.Value value -> value;
        }).toList();
        return TypeRef.of(ref.name(), arguments, ref.annotations(), ref.closure());
    }

    private static RecordTemplateField field(String head, RecordTemplateField field,
            Map<String, TypeArgument> bindings, List<String> outer) {
        // A sibling names a field of the record, never a parameter, so substitution leaves it as written.
        Optional<Reference> type = field.type().map(written -> switch (written) {
            case TypeRef reference -> reference(reference, bindings);
            case SiblingRef sibling -> sibling;
        });
        Optional<String> typeParam = Optional.empty();
        if (field.typeParam().isPresent()) {
            String parameter = field.typeParam().get();
            switch (bound(field.typeParam().get(), bindings)) {
                case TypeArgument.Ref ref when ref.ref().arguments().isEmpty()
                        && outer.contains(ref.ref().name()) -> typeParam = Optional.of(ref.ref().name());
                case TypeArgument.Ref ref -> type = Optional.of(ref.ref());
                case TypeArgument.Value value when value.value().form() == Token.Form.UNQUOTED
                        && outer.contains(value.value().text()) -> typeParam = Optional.of(value.value().text());
                case TypeArgument.Value value -> throw new SchemaValidationException("'" + head + "<...>' binds the "
                        + "type parameter '" + parameter + "' to '" + value.value().text() + "', which is no type -- "
                        + IdentifierGrammar.validate(value.value().text())
                                .orElse("a quoted token is a literal, never a name")
                        + " (§5.10)");
            }
        }
        Optional<Token> value = field.value();
        Optional<String> valueParam = Optional.empty();
        if (field.valueParam().isPresent()) {
            String parameter = field.valueParam().get();
            switch (bound(parameter, bindings)) {
                case TypeArgument.Value literal when literal.value().form() == Token.Form.UNQUOTED
                        && outer.contains(literal.value().text()) -> valueParam = Optional.of(literal.value().text());
                case TypeArgument.Value literal -> value = Optional.of(literal.value());
                case TypeArgument.Ref ref when ref.ref().arguments().isEmpty()
                        && outer.contains(ref.ref().name()) -> valueParam = Optional.of(ref.ref().name());
                case TypeArgument.Ref ref when ref.ref().arguments().isEmpty() ->
                        value = Optional.of(new Token(ref.ref().name(), Token.Form.UNQUOTED));
                case TypeArgument.Ref ref -> throw new SchemaValidationException("'" + head + "<...>' binds the "
                        + "value parameter '" + parameter + "' to '" + ParameterTypes.spell(ref.ref()) + "', an "
                        + "application, where a field's value stands -- a value parameter's argument is a token "
                        + "(§5.10)");
            }
        }
        if (field.valueParam().isPresent() && value.isPresent()) {
            Optional<String> open = typeParam.isPresent() ? typeParam : switch (type.orElseThrow()) {
                case TypeRef reference -> OpenEntry.parameterIn(reference, outer);
                case SiblingRef ignored -> Optional.<String>empty();
            };
            if (open.isPresent()) {
                // A literal needs a closed type, and the type the argument is a value of is still a parameter here.
                throw new SchemaValidationException("'" + head + "<...>' binds '" + field.valueParam().get()
                        + "' to the literal '" + value.get().text() + "', whose type is '" + open.get() + "', still "
                        + "open here -- a literal needs a closed type: pass a parameter for '"
                        + field.valueParam().get() + "' too, or close '" + open.get() + "' (§5.10)");
            }
        }
        return new RecordTemplateField(type, typeParam, field.optional(), field.voidable(), field.role(), value,
                valueParam, field.annotations(), field.position());
    }

    private static TypeArgument bound(String parameter, Map<String, TypeArgument> bindings) {
        TypeArgument argument = bindings.get(parameter);
        if (argument == null) {
            throw new IllegalStateException("parameter '" + parameter + "' has no argument -- arity is checked "
                    + "before a template is substituted");
        }
        return argument;
    }
}
