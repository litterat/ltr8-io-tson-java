package io.ltr8.tson.mapper;

import io.ltr8.tson.parser.ast.Annotation;

import java.util.List;
import java.util.Optional;

/**
 * The wire-format annotations (§3.1's {@code @name[:value]} metadata) on the value a record marked
 * {@code io.ltr8.annotation.Annotated} was bound from -- the required carrier type for that
 * annotation's one component. Deliberately minimal: a caller with a specific name in mind gets a
 * one-line lookup; a caller who needs something this doesn't offer yet (resolving a value into a
 * Java type, matching by anything other than an exact name) still has the raw, ordered {@link
 * #values()} to work with directly. Expected to grow -- kept small on purpose so it's easy to grow
 * in whatever direction turns out to be needed, not because the querying need is expected to stay
 * this simple.
 *
 * <p>Only ever holds the annotations on the value the whole record corresponds to -- not on its
 * individual field values, which have no equivalent carrier (a bare {@code String} field has
 * nowhere in Java to hold annotations of its own). See {@code SPEC-FEEDBACK.md} for why that's a
 * deliberate scope limit, not an oversight: recovering those requires the parsed AST directly
 * ({@code DataValue#annotations()} on the field's own value), not this type.
 */
public record TsonAnnotations(List<Annotation> values) {

    public TsonAnnotations {
        values = List.copyOf(values);
    }

    /** The first annotation named {@code name}, in source order -- absent if there is none. */
    public Optional<Annotation> get(String name) {
        return values.stream().filter(a -> a.name().equals(name)).findFirst();
    }

    /** Every annotation named {@code name}, in source order -- §3.1 permits a name to repeat. */
    public List<Annotation> getAll(String name) {
        return values.stream().filter(a -> a.name().equals(name)).toList();
    }
}
