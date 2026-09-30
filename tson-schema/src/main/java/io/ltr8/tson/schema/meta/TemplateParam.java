package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.Objects;
import java.util.Optional;

/**
 * One parameter of an open entry ({@code template_param}, [TSON-SCHEMA] §5.10, §8.1): its name, the type an
 * argument for it is read as, and -- for a type parameter -- the bound an argument's type must IS-A.
 *
 * <p><b>{@link #type} is derived from the positions the parameter stands in</b>: a type slot gives
 * {@code type_ref}, a scalar slot of the applied constructor gives that slot's declared type, a routed default or
 * fixed value gives the field's own declared type, and a parameter riding another template's argument list takes
 * that template's recorded type for the position. So the parameter's kind is not a separate field: it is a type
 * parameter exactly when {@link #type} is {@code type_ref}.
 *
 * <p><b>A declaration may narrow what it derives</b>, {@code <T: text, N: int8>}, read by the kind the positions
 * give: on a value parameter it narrows {@link #type}; on a type parameter it is {@link #bound}. The two kinds
 * record different facts -- a value's type, and a restriction on which types a reference may name -- which is why
 * {@link #bound} is its own field rather than a second meaning of {@link #type}.
 *
 * <p>A {@code type} may name an earlier parameter of the same template -- {@code <T, N> { w?: T ~ N }} records
 * {@code N}'s type as {@code T} -- since the value's type is whatever that parameter's argument names.
 */
@Typename(name = "template_param")
public record TemplateParam(String name, TypeRef type, Optional<TypeRef> bound) {

    /** The kernel type of a type reference: what a type parameter's argument is read as. */
    public static final TypeRef TYPE_REF = TypeRef.of("type_ref");

    @Record
    public TemplateParam {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(bound, "bound");
        if (bound.isPresent() && !TYPE_REF.equals(type)) {
            throw new IllegalArgumentException("parameter '" + name + "' is a value parameter (its type is "
                    + type + "), and only a type parameter has a bound");
        }
    }

    /** An unbounded parameter of the given type. */
    public TemplateParam(String name, TypeRef type) {
        this(name, type, Optional.empty());
    }

    /** A parameter whose type is not yet derived, or derives to nothing narrower: an unbounded type parameter. */
    public static TemplateParam typeParameter(String name) {
        return new TemplateParam(name, TYPE_REF);
    }

    /** Whether an argument for this parameter is a type reference rather than a value (§5.10's two kinds). */
    public boolean isTypeParameter() {
        return TYPE_REF.equals(type);
    }
}
