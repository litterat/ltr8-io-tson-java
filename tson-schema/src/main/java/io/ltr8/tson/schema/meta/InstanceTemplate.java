package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The meta-kernel's {@code instance_template} (Part 2 §8.1): the <b>open</b> counterpart of a constructor
 * application -- the constructor an entry will build, and the bindings it will build it from, while at least
 * one of those bindings is still a parameter. An author writes {@code <T> !array { element_type: T }}, or the
 * sugar {@code [T]} inside a template, and the resolver produces this.
 *
 * <p><b>An entry's body is an {@code instance_template} exactly when the entry is open</b>, and that
 * equivalence is the point. {@code array}'s {@code element_type} is a {@code type_ref} and may already name a
 * parameter, so {@code <T> !array { element_type: T }} could carry an ordinary {@link ArrayBody}; the sized
 * form could not, since {@code min_items} is declared {@code integer?} and no {@code integer} is a parameter.
 * Using this uniformly leaves one open representation instead of two, and makes "open" readable off the body.
 * Materialisation is the transition: once substitution leaves no {@link TemplateArgument.Param}, the bindings
 * bind through the constructor's own reader and the result is an ordinary body.
 *
 * <p>Composes with {@code top} directly and carries no {@code ~}, exactly as {@link Reference} does, and for
 * the same reason both times. {@link Product} would oblige it to supply {@code access_pattern}/{@code
 * size_type}, and an {@code instance_template} never describes a value -- no data value ever has one as its
 * type. The missing {@code ~} matters equally: a constructor is what a schema applies through {@code !C
 * value}, and nobody writes {@code foo => !instance_template { ... }}; the resolver produces this, the way it
 * produces a {@link Reference}.
 *
 * <p>{@code target}, not {@code constructor}: {@link TypeDefinition#constructor} is already a boolean flag,
 * and {@code reference} already uses {@code target} for the thing an entry points at.
 */
@Typename(name = "instance_template")
public record InstanceTemplate(String target, Map<String, TemplateArgument> bindings) implements Top {

    /** Insertion-ordered, so a written body and its resolved form list their bindings the same way. */
    public InstanceTemplate {
        bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
    }
}
