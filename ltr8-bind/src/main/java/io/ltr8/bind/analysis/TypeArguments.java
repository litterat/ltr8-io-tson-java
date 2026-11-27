package io.ltr8.bind.analysis;

import io.ltr8.bind.DataParameterizedType;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
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

    /**
     * Each type parameter of {@code clss}'s super classes bound to the argument {@code clss} supplies it, in terms of
     * {@code clss}'s own type parameters, which stay unbound, as does a parameter of a class extended raw.
     */
    static Map<TypeVariable<?>, Type> inherited(Class<?> clss) {
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        for (Class<?> sub = clss; sub.getSuperclass() != null; sub = sub.getSuperclass()) {
            if (sub.getGenericSuperclass() instanceof ParameterizedType parameterized) {
                TypeVariable<?>[] parameters = sub.getSuperclass().getTypeParameters();
                Type[] arguments = parameterized.getActualTypeArguments();
                for (int i = 0; i < parameters.length; i++) {
                    bindings.put(parameters[i], substitute(arguments[i], bindings));
                }
            }
        }
        return bindings;
    }

    /**
     * Whether two types are one, compared by structure: a {@link ParameterizedType} {@link #substitute} builds has no
     * owner type and is not equal to the JDK's of the same type where that has one.
     */
    static boolean sameType(Type a, Type b) {
        if (a instanceof ParameterizedType pa && b instanceof ParameterizedType pb) {
            Type[] x = pa.getActualTypeArguments();
            Type[] y = pb.getActualTypeArguments();
            if (pa.getRawType() != pb.getRawType() || x.length != y.length) {
                return false;
            }
            for (int i = 0; i < x.length; i++) {
                if (!sameType(x[i], y[i])) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof GenericArrayType ga && b instanceof GenericArrayType gb) {
            return sameType(ga.getGenericComponentType(), gb.getGenericComponentType());
        }
        return a.equals(b);
    }

    static Class<?> erasure(Type type) {
        return switch (type) {
            case Class<?> c -> c;
            case ParameterizedType p -> (Class<?>) p.getRawType();
            case GenericArrayType g -> erasure(g.getGenericComponentType()).arrayType();
            case TypeVariable<?> v -> erasure(v.getBounds()[0]);
            case WildcardType w -> erasure(w.getUpperBounds()[0]);
            default -> Object.class;
        };
    }

    static Type substitute(Type type, Map<TypeVariable<?>, Type> bindings) {
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
