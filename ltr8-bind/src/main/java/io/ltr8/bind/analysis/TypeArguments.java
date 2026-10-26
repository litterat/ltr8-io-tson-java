package io.ltr8.bind.analysis;

import io.ltr8.bind.DataParameterizedType;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A generic class's components at one parameterisation: each type parameter a component's declared type names is
 * replaced by the argument the class is described with. {@code record Box<T>(List<T> items)} described as {@code
 * Box<String>} binds {@code items} as a {@code List<String>}, which is what lets a generic value class -- a map of
 * names whose value type is the parameter -- be a component of another class at all: a type variable left in place
 * has no descriptor.
 *
 * <p>Only a component whose declared type is parameterised is rewritten, at any depth of its arguments. A
 * component whose type is a bare type variable is left as declared, its erasure, and a parameter the description
 * does not supply (a raw class) is left unreplaced.
 */
final class TypeArguments {

    private TypeArguments() {
    }

    /** {@code components}, found on {@code targetClass}, rewritten for {@code parameterizedType}'s arguments. */
    static void substitute(Class<?> targetClass, Type parameterizedType, List<ComponentInfo> components) {
        if (!(parameterizedType instanceof ParameterizedType parameterized)
                || parameterized.getRawType() != targetClass) {
            return;
        }
        TypeVariable<?>[] parameters = targetClass.getTypeParameters();
        Type[] arguments = parameterized.getActualTypeArguments();
        if (parameters.length != arguments.length) {
            return;
        }
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        for (int i = 0; i < parameters.length; i++) {
            bindings.put(parameters[i], arguments[i]);
        }
        for (ComponentInfo component : components) {
            if (component.getParamType() != null) {
                component.setParamType((ParameterizedType) substitute(component.getParamType(), bindings));
            }
        }
    }

    private static Type substitute(Type type, Map<TypeVariable<?>, Type> bindings) {
        if (type instanceof TypeVariable<?> variable) {
            return bindings.getOrDefault(variable, variable);
        }
        if (type instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            boolean changed = false;
            for (int i = 0; i < arguments.length; i++) {
                Type replaced = substitute(arguments[i], bindings);
                changed |= replaced != arguments[i];
                arguments[i] = replaced;
            }
            return changed ? new DataParameterizedType(parameterized.getRawType(), arguments) : parameterized;
        }
        return type;
    }
}
