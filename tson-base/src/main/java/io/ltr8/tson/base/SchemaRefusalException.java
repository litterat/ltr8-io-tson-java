package io.ltr8.tson.base;

import java.util.Objects;

/**
 * <b>The processor declines the author's schema</b> under its [TSON-DATA] §8.2 identifier policy: a name in it
 * reads like another in the same scope, carries a character outside its identifier profile, or mixes scripts the
 * policy does not admit. {@link #code()} is which of the three rules fired.
 *
 * <p>A {@link SchemaValidationException}, because it is still a verdict on the schema and is collected wherever
 * one is -- the verdict does not change when this library improves. A type of its own, because §8.2 requires a
 * consumer be able to tell a refusal from a malformed schema, and a reporting overload turns this into
 * {@link Diagnostic.Code#CONFUSABLE_NAMES}, {@link Diagnostic.Code#RESTRICTED_CHARACTER} or
 * {@link Diagnostic.Code#RESTRICTED_SCRIPT} rather than {@code SCHEMA_ERROR}.
 */
public final class SchemaRefusalException extends SchemaValidationException {

    private static final long serialVersionUID = 1L;

    private final Diagnostic.Code code;

    /** @param code the §8.2 rule that refused the name -- one of {@link Diagnostic.Code#isNameRefusal()}'s */
    public SchemaRefusalException(Diagnostic.Code code, String message, Throwable cause) {
        super(message, cause);
        if (!Objects.requireNonNull(code, "code").isNameRefusal()) {
            throw new IllegalArgumentException(code + " is not a name-hygiene refusal");
        }
        this.code = code;
    }

    public Diagnostic.Code code() {
        return code;
    }
}
