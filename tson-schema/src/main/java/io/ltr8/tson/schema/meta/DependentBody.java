package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.Objects;

/**
 * The kernel's {@code meta.dependent}: the sum in which the field {@link #on} of the enclosing record instance names
 * the type, where {@link DeclaredType}'s instance takes it from the value's own tag ([TSON-SCHEMA] §5.2). It is a
 * variable bound by the record it stands in, so it means something only there: it is written in place, directly at a
 * record field's type ({@link TypeInline}), and never declared.
 *
 * <p>Two of these with the same {@link #on} in two records are equal as values and are two types, one per binder.
 * Anything that shares work by a type in place's structural identity keys a dependent one by its record as well, or
 * never meets one: a record field holding one is read through the record's own dependent-field reader.
 */
@Typename(name = "meta.dependent")
public record DependentBody(TypeChain chain, String on) implements Sum {

    @Record
    public DependentBody {
        chain = TypeChain.orEmpty(chain);
        Objects.requireNonNull(on, "on");
        if (on.isEmpty()) {
            throw new IllegalArgumentException("a dependent type names a field");
        }
    }

    /** The dependent type over the field {@code on}, placed in no chain. */
    public static DependentBody on(String on) {
        return new DependentBody(TypeChain.EMPTY, on);
    }

    /** The source spelling, {@code kind!}. */
    public String text() {
        return on + "!";
    }

    @Override
    public DependentBody withChain(TypeChain chain) {
        return new DependentBody(chain, on);
    }
}
