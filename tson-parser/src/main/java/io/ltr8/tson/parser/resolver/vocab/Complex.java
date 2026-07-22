package io.ltr8.tson.parser.resolver.vocab;

import java.math.BigDecimal;

/**
 * meta-kernel's {@code complex} host value -- a real/imaginary pair. {@code complex_type.component}
 * (meta.tn1) selects the type of both parts from a closed vocabulary (default {@code NUMBER}, i.e.
 * exact {@link BigDecimal}; {@code INTEGER}/{@code RATIONAL}/{@code FLOAT32}/{@code FLOAT64} are the
 * other members) -- this class only implements the default ({@code NUMBER}) case, since {@code
 * complex}'s built-in instance (§5.6's {@code !complex}) is always the unconstrained {@code
 * complex_type} instance and never refines {@code component}; a schema (Part 2) narrowing it to a
 * different component type is separate, not-yet-relevant work.
 *
 * <p>Unlike {@link Rational}, there's no meta.tn1 doc calling for value-based equality here, so this
 * uses the record default (field-based, {@code BigDecimal.equals}, which is itself scale-sensitive)
 * -- each component is its own independently-exact {@code NUMBER}-typed value, preserved as written
 * the same way a bare {@code !number} is, not normalized across instances.
 *
 * <p>As with {@link Rational}, {@code tson-mapper} cannot bind directly to this class -- it's itself
 * a Java record, so {@code tson-bind}'s record auto-detection claims it ahead of the vocabulary
 * path. A {@code DataBridge<Complex, TheirType>} registered via {@code
 * DataBindContext.registerAtom(TheirType.class, bridge)} is the supported (and currently only) way
 * to bind {@code !complex} to a Java field -- the natural fit for an application that already has a
 * richer complex type (e.g. Apache Commons Math's {@code Complex}) -- see {@link ComplexType}'s
 * Javadoc.
 */
public record Complex(BigDecimal real, BigDecimal imaginary) {
}
