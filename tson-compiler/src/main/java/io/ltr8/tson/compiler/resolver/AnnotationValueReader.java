package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.DataValue;

/**
 * Reads an annotation's value through the type its name refers to (Part 2 §6: "an annotation {@code @T} (or
 * {@code @T:value}) names a type {@code T}", with the value "validated against {@code T}'s contract").
 * {@code type} is the annotation's own name, resolved one hop against the governing target's namespace by
 * the caller; {@code value} is the authored data value.
 *
 * <p>Separate from {@link DefinitionMetaReader} despite the identical parameters, because the two read
 * genuinely different things and only one of them has a bounded return. A constructor body always binds to a
 * {@code schema.meta} type, so that hook returns {@link io.ltr8.tson.schema.meta.Top}; an annotation binds to
 * whatever its name resolves to -- {@code @doc:"..."} yields a {@code String} -- so this one returns {@code
 * Object}. Widening the single hook to cover both would have taken the type information away from the
 * constructor case, which has it, to accommodate the annotation case, which cannot.
 *
 * <p>A resolver with no compiled reader to offer supplies one that returns {@code null}, which the caller
 * treats as "value out of reach" and keeps the annotation's name alone.
 */
@FunctionalInterface
interface AnnotationValueReader {

    Object read(String type, DataValue value);
}
