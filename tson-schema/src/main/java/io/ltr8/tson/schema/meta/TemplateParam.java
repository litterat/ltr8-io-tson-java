package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.Objects;
import java.util.Optional;

/**
 * One parameter of an open entry ({@code meta.template_param}, [TSON-SCHEMA] §5.10, §8.1): its name, and the bound an
 * argument's type must IS-A.
 *
 * <p><b>A parameter is a type.</b> It stands only at a slot typed {@code meta.type_ref} or as an argument of an
 * application, and an argument for it is a reference, so it records no type of its own: what an argument is read as is
 * always {@code meta.type_ref}.
 *
 * <p><b>{@link #bound} is written or inherited</b>: a declaration writes {@code <T: text>}, and a parameter passed to
 * another template inherits that template's bound for the position.
 */
@Typename(name = "meta.template_param")
public record TemplateParam(String name, Optional<TypeRef> bound) {

    @Record
    public TemplateParam {
        Objects.requireNonNull(name, "name");
        bound = bound == null ? Optional.empty() : bound;
    }

    /** An unbounded parameter. */
    public TemplateParam(String name) {
        this(name, Optional.empty());
    }
}
