package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.util.Objects;

/**
 * One parameter of an open entry ({@code template_param}, [TSON-SCHEMA] §5.10, §8.1): its name, and the type an
 * argument for it is read as.
 *
 * <p><b>{@link #type} is derived from the positions the parameter stands in</b>, never written: a type slot gives
 * {@code type_ref}, a scalar slot of the applied constructor gives that slot's declared type, a routed default or
 * fixed value gives the field's own declared type, and a parameter riding another template's argument list takes
 * that template's recorded type for the position. So the parameter's kind is not a second field: it is a type
 * parameter exactly when {@link #type} is {@code type_ref}.
 *
 * <p>A {@code type} may name an earlier parameter of the same template -- {@code <T, N> { w?: T ~ N }} records
 * {@code N}'s type as {@code T} -- since the value's type is whatever that parameter's argument names.
 */
@Typename(name = "template_param")
public record TemplateParam(String name, TypeRef type) {

    /** The kernel type of a type reference: what a type parameter's argument is read as. */
    public static final TypeRef TYPE_REF = TypeRef.of("type_ref");

    public TemplateParam {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }

    /** A parameter whose type is not yet derived, or derives to nothing narrower: a type parameter. */
    public static TemplateParam typeParameter(String name) {
        return new TemplateParam(name, TYPE_REF);
    }

    /** Whether an argument for this parameter is a type reference rather than a value (§5.10's two kinds). */
    public boolean isTypeParameter() {
        return TYPE_REF.equals(type);
    }
}
