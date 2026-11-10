package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Annotations;

import java.util.List;
import java.util.Objects;

/**
 * A reference that applies a head to arguments, {@code result<T>}: a {@link TypeRef} with at least one argument.
 * Built through {@link TypeRef#of}, which chooses it for a non-empty argument list.
 *
 * <p>{@code arguments} is copied and refused empty: there is no spelling of an empty argument list, and a reference
 * without arguments is a {@link NameRef}. {@code annotations} normalises {@code null} to empty and {@code closure} to
 * {@link TypeRef.Closure#NONE}. Identity is the name and the arguments; see {@link TypeRef}.
 */
public record ApplicationRef(String name, List<TypeRef> arguments, Annotations annotations,
                             TypeRef.Closure closure) implements TypeRef {

    public ApplicationRef {
        Objects.requireNonNull(name, "name");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("an application has at least one argument: " + name);
        }
        annotations = annotations == null ? Annotations.empty() : annotations;
        closure = closure == null ? Closure.NONE : closure;
    }

    @Override
    public ApplicationRef withAnnotations(Annotations annotations) {
        return new ApplicationRef(name, arguments, annotations, closure);
    }

    @Override
    public ApplicationRef withClosure(Closure closure) {
        List<TypeRef> carried = arguments.stream().map(argument -> argument.withClosure(closure)).toList();
        return new ApplicationRef(name, carried, annotations, closure);
    }

    /** By name and arguments -- {@code annotations} and {@code closure} change no reference's identity. */
    @Override
    public boolean equals(Object o) {
        return o instanceof ApplicationRef other && name.equals(other.name) && arguments.equals(other.arguments);
    }

    /** The hash of the name and the arguments -- see {@link #equals}. */
    @Override
    public int hashCode() {
        return Objects.hash(name, arguments);
    }

    /** The name, the arguments and the annotations; the closure is no part of the value. */
    @Override
    public String toString() {
        return "TypeRef[name=" + name + ", arguments=" + arguments + ", annotations=" + annotations + "]";
    }
}
