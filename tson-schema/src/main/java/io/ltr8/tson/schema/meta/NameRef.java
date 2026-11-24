package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Annotations;

import java.util.Objects;

/**
 * A reference that is a qualified name, {@code geo.address}: a {@link TypeRef} naming an entry. Built through {@link
 * TypeRef#of}.
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

    /** The hash of the name -- see {@link #equals}. */
    @Override
    public int hashCode() {
        return Objects.hash(NameRef.class, name);
    }

    /** The name and the annotations; the closure is no part of the value. */
    @Override
    public String toString() {
        return "TypeRef[name=" + name + ", annotations=" + annotations + "]";
    }
}
