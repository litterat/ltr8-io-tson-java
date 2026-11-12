package io.ltr8.tson.compiler.resolver;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.tson.schema.meta.ApplicationRef;
import io.ltr8.tson.schema.meta.ParamRef;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.TypeSlots;

import java.util.List;
import java.util.Map;

/**
 * An open record's parameters substituted on the model ([TSON-SCHEMA] §5.10): each {@link ParamRef} in its fields'
 * types and its parents replaced by the argument bound to it, at any depth, and nothing else changed. A parameter
 * stands only at a slot of a field's type ({@link TypeSlots}) or as an argument of an application there or in a parent,
 * so those references are all substitution visits.
 *
 * <p><b>Two callers, one rule.</b> {@code TemplateMaterialiser} closes an application, where every argument is closed;
 * {@code DefinitionResolver} absorbs an applied operand, where an argument may be one of the absorbing declaration's
 * own parameters, written as its name, which that declaration's record then holds as its parameter in turn.
 */
final class Substitution {

    private Substitution() {
    }

    /**
     * {@code open}'s fields with {@code bindings} substituted at every slot of their types, at any depth of types
     * written in place, in order and with their key annotations.
     */
    static AnnotatedMap<String, RecordField> fields(RecordBody open, Map<String, TypeRef> bindings) {
        TypeSlots.Rewrite substitute = reference -> reference(reference, bindings);
        return open.mapFields(field -> field.withType(TypeSlots.map(field.type(), substitute)));
    }

    /** {@code open}'s parents as written, each substituted -- closing completes the chain from them. */
    static List<TypeRef> parents(RecordBody open, Map<String, TypeRef> bindings) {
        return open.parents().stream().map(parent -> reference(parent, bindings)).toList();
    }

    /** {@code ref} with every parameter in it replaced by its binding, at any depth. */
    static TypeRef reference(TypeRef ref, Map<String, TypeRef> bindings) {
        return switch (ref) {
            case ParamRef parameter -> bound(parameter.name(), bindings);
            case ApplicationRef application -> TypeRef.of(application.name(), application.arguments().stream()
                    .map(argument -> reference(argument, bindings)).toList(), application.annotations(),
                    application.closure());
            default -> ref;
        };
    }

    private static TypeRef bound(String parameter, Map<String, TypeRef> bindings) {
        TypeRef argument = bindings.get(parameter);
        if (argument == null) {
            throw new IllegalStateException("parameter '" + parameter + "' has no argument -- arity is checked "
                    + "before an open record is substituted");
        }
        return argument;
    }
}
