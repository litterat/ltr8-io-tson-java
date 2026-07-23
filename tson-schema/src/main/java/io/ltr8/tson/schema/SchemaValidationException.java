package io.ltr8.tson.schema;

/**
 * A resolved schema failed the consistency checks {@link SchemaRegistry#register} runs before a
 * schema is admitted (Part 2 §3.4.1's Pass 2 validation, and the canonical-identity profile of
 * {@code [TSON-DATA] §2.2.1}) -- an unresolved reference, a malformed/non-canonical {@code !!id},
 * an {@code !!import} list (not yet supported), or a duplicate registration under an already-used
 * identity. Unchecked, matching {@code LexException}/{@code ParseException}'s own established
 * shape elsewhere in this codebase.
 */
public final class SchemaValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SchemaValidationException(String message) {
        super(message);
    }
}
