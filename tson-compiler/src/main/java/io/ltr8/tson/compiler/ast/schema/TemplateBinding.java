package io.ltr8.tson.compiler.ast.schema;

/**
 * {@code template-bind = field-name ws ":" ws template-arg} (Part 2 §12.1) -- one slot of an
 * {@link InstanceTemplate}: the constructor field being bound, and what it is bound to.
 *
 * <p>The value is a {@link TypeArg}, which is already exactly what {@code template-arg} admits -- a bare
 * name, a name carrying type arguments, or a literal. Whether a bare name is a *parameter* or a type is not
 * decided here: §12.1 defers that classification to resolution, where the enclosing declaration's parameter
 * list is what answers it.
 */
public record TemplateBinding(String name, TypeArg value) {
}
