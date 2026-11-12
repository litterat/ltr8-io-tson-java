package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Annotations;

import java.util.Objects;

/**
 * A parameter of the enclosing open record, written as its name ([TSON-SCHEMA] §5.10): the other kind of reference
 * value, beside a name. Inside an open record a reference's name is looked up
 * in the record's {@code parameters} before the namespace (§3.3), and one found there is this: a name that resolves to
 * no entry.
 *
 * <p><b>A {@link TypeRef}</b>, because a parameter stands where a type is named: at a slot of a field's {@code type},
 * at any depth of a type written in place, and as an argument of an application ({@link ApplyBody}) there or in a
 * parent. It is never applied (a parameter stands for a type, never for a template) and carries no closure: what it
 * names differs from one application to the next.
 *
 * <p><b>Only an open record holds one.</b> {@link RecordBody} turns every name among its fields' types and its
 * parents that one of its {@code parameters} declares into one of these as it is built, from source and from a
 * resolved form alike, and refuses one its parameters do not declare; an application, substituting each away, closes
 * to a record with none.
 *
 * <p>{@code annotations} normalises {@code null} to empty. Identity is the name, and a parameter is never equal to
 * the {@link NameRef} spelled the same: the one is a slot an application fills, the other an entry of the namespace.
 */
public record ParamRef(String name, Annotations annotations) implements TypeRef {

    public ParamRef {
        Objects.requireNonNull(name, "name");
        annotations = annotations == null ? Annotations.empty() : annotations;
    }

    /** The unannotated parameter {@code name}. */
    public static ParamRef of(String name) {
        return new ParamRef(name, Annotations.empty());
    }

    /** Always {@link TypeRef.Closure#NONE}: a parameter names no entry. */
    @Override
    public Closure closure() {
        return Closure.NONE;
    }

    @Override
    public ParamRef withAnnotations(Annotations annotations) {
        return new ParamRef(name, annotations);
    }

    /** This parameter itself: it names nothing, in any closure. */
    @Override
    public ParamRef withClosure(Closure closure) {
        return this;
    }

    /** By name -- {@code annotations} change no reference's identity. */
    @Override
    public boolean equals(Object o) {
        return o instanceof ParamRef other && name.equals(other.name);
    }

    /** Apart from a {@link NameRef}'s of the same name, as {@link #equals} is. */
    @Override
    public int hashCode() {
        return Objects.hash(ParamRef.class, name);
    }

    /** The name and the annotations. */
    @Override
    public String toString() {
        return "ParamRef[name=" + name + ", annotations=" + annotations + "]";
    }
}
