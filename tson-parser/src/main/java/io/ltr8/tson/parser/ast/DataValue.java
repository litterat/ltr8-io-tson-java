package io.ltr8.tson.parser.ast;

import java.util.List;
import java.util.Optional;

/**
 * {@code data-value = *annotation [type-ref] core-value} (§2.3, §7.4) — zero or more
 * annotations, an optional type reference, and a core value. Occurs everywhere a value does:
 * as the document root, as a map key, and as the payload of a {@link ScopedValue}.
 *
 * <p>{@code typeRef} is preserved verbatim, uninterpreted: resolving it against the built-in
 * type vocabulary (§5) or a declared schema ([TSON-SCHEMA]) is a later layer's job. A Class 1
 * processor "MUST preserve type annotations it does not resolve" (§3.2).
 */
public record DataValue(List<Annotation> annotations, Optional<String> typeRef, CoreValue coreValue) {

    public DataValue {
        annotations = List.copyOf(annotations);
    }
}
