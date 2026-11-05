package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Annotations;

import java.util.List;
import java.util.Objects;

/**
 * A reference that is a qualified name alone, {@code geo.address}: a {@link TypeRef} with no arguments. Built through
 * {@link TypeRef#of}, which chooses it for an empty argument list.
 *
 * <p>{@code annotations} normalises {@code null} to empty and {@code closure} to {@link TypeRef.Closure#NONE}.
 * Identity is the name alone; see {@link TypeRef}.
 */
public record NameRef(String name, Annotations annotations, TypeRef.Closure closure) implements TypeRef {

    public NameRef {
        Objects.requireNonNull(name, "name");
        annotations = annotations == null ? Annotations.empty() : annotations;
        closure = closure == null ? Closure.NONE : closure;
    }

    /** Always empty: a name carries no arguments. */
    @Override
    public List<TypeArgument> arguments() {
        return List.of();
    }

    @Override
    public NameRef withAnnotations(Annotations annotations) {
        return new NameRef(name, annotations, closure);
    }

    @Override
    public NameRef withClosure(Closure closure) {
        return new NameRef(name, annotations, closure);
    }

    /** By name -- {@code annotations} and {@code closure} change no reference's identity. */
    @Override
    public boolean equals(Object o) {
        return o instanceof NameRef other && name.equals(other.name);
    }

    /** The hash of the name and the empty argument list -- see {@link #equals}. */
    @Override
    public int hashCode() {
        return Objects.hash(name, List.of());
    }

    /** The name, the empty arguments and the annotations; the closure is no part of the value. */
    @Override
    public String toString() {
        return "TypeRef[name=" + name + ", arguments=" + List.of() + ", annotations=" + annotations + "]";
    }
}
