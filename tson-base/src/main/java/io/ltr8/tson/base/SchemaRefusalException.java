package io.ltr8.tson.base;

import java.util.Objects;

/**
 * <b>The processor declines the author's schema</b> under its policy: under its [TSON-DATA] §8.2 identifier policy, a
 * name in it reads like another in the same scope, carries a character outside its identifier profile, or mixes
 * scripts the policy does not admit; under its limits ([TSON-SCHEMA] §11.5), resolving it would run past one. {@link
 * #code()} is which rule fired.
 *
 * <p>A {@link SchemaValidationException}, because it is collected wherever a verdict on the schema is -- neither
 * changes when this library improves. A type of its own, because a consumer must be able to tell a refusal from a
 * malformed schema (§8.2 requires it of a name refusal, and a limit is the deployment's choice as much), and a
 * reporting overload turns this into {@link Diagnostic.Code#CONFUSABLE_NAMES}, {@link
 * Diagnostic.Code#RESTRICTED_CHARACTER}, {@link Diagnostic.Code#RESTRICTED_SCRIPT} or {@link
 * Diagnostic.Code#LIMIT_EXCEEDED} rather than {@code SCHEMA_ERROR}.
 */
public final class SchemaRefusalException extends SchemaValidationException {

    private static final long serialVersionUID = 1L;

    private final Diagnostic.Code code;

    /**
     * @param code the rule that refused the schema -- one of {@link Diagnostic.Code#isNameRefusal()}'s, or {@link
     *             Diagnostic.Code#LIMIT_EXCEEDED}
     */
    public SchemaRefusalException(Diagnostic.Code code, String message, Throwable cause) {
        super(message, cause);
        if (!Objects.requireNonNull(code, "code").isNameRefusal() && code != Diagnostic.Code.LIMIT_EXCEEDED) {
            throw new IllegalArgumentException(code + " is neither a name-hygiene refusal nor a limit's");
        }
        this.code = code;
    }

    public Diagnostic.Code code() {
        return code;
    }
}
