package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.Objects;
import java.util.Optional;

/**
 * One parameter of an open record ({@code meta.param}, [TSON-SCHEMA] §5.10, §8.1): its name, and the bound an
 * argument must IS-A.
 *
 * <p><b>A parameter is a type.</b> It is written where it stands as its name, a reference value ({@link ParamRef}),
 * and stands at a field's {@code type}, or as an argument of an application there or in a parent, and nowhere else.
 * An argument for it is a reference, so it records no type of its own.
 *
 * <p><b>{@link #bound} is what the declaration writes</b>, {@code <T: text>}, and otherwise absent: nothing about a
 * parameter is inferred, so a parameter is known in full where it is declared.
 */
@Typename(name = "meta.param")
public record Param(String name, Optional<TypeRef> bound) {

    @Record
    public Param {
        Objects.requireNonNull(name, "name");
        bound = bound == null ? Optional.empty() : bound;
    }

    /** An unbounded parameter. */
    public Param(String name) {
        this(name, Optional.empty());
    }
}
