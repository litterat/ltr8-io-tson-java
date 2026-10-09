package io.ltr8.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a component that no schema field fills, so binding leaves it to the class rather than
 * reporting it as a mismatch.
 *
 * <p><b>Why an explicit marker rather than silence.</b> A component the governing schema declares no field
 * for reaches the constructor as {@code null} however careful the class is -- the argument array is sized by
 * the Java class and filled only through matched schema fields. Silently, that is a field mysteriously
 * holding its default, found in testing if at all; reported, it is a wiring mistake fixed where the schema
 * meets the class. But some components genuinely belong to the class and not to the wire -- a source
 * position kept for diagnostics, a cache, anything derived -- and those need a way to say so once, at the
 * component, instead of turning strictness off for the whole read.
 *
 * <p>Distinct from {@link Field}, which renames a component that <em>is</em> bound. This says there is
 * nothing to bind, and the {@code null} that arrives is the class's own business.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface Unbound {
}
